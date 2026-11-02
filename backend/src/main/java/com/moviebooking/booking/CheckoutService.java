package com.moviebooking.booking;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.moviebooking.booking.BookingService.BookingConfirmation;
import com.moviebooking.booking.CheckoutTransactions.Confirmation;
import com.moviebooking.booking.CheckoutTransactions.StartedAttempt;
import com.moviebooking.config.ApiException;
import com.moviebooking.payment.DeclineReason;
import com.moviebooking.payment.PaymentScenario;
import com.moviebooking.payment.PaymentStatus;
import com.moviebooking.payment.PaymentView;
import com.moviebooking.payment.ProviderChargeStatus;
import com.moviebooking.payment.SimulatedPaymentProvider;
import com.moviebooking.security.AuthenticatedUser;

/**
 * Orchestrates checkout (docs/architecture.md §6.4–§6.6). Deliberately not transactional: each
 * database step is a separate {@link CheckoutTransactions} call that commits before the payment
 * provider is called, so no connection or row lock is held during the provider call.
 */
@Service
public class CheckoutService {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final CheckoutTransactions transactions;
    private final SimulatedPaymentProvider provider;
    private final BookingService bookingService;
    private final Clock clock;
    private final Duration paymentTimeout;

    public CheckoutService(CheckoutTransactions transactions, SimulatedPaymentProvider provider,
            BookingService bookingService, Clock clock,
            @Value("${booking.payment-timeout}") Duration paymentTimeout) {
        this.transactions = transactions;
        this.provider = provider;
        this.bookingService = bookingService;
        this.clock = clock;
        this.paymentTimeout = paymentTimeout;
    }

    /**
     * Pays for a hold. A new idempotency key starts one attempt and charges once; a known key
     * returns that attempt (resolving it first if its outcome is overdue) and never charges again.
     */
    public PaymentOutcome pay(AuthenticatedUser customer, UUID holdId, String idempotencyKey,
            PaymentScenario scenario) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw ApiException.badRequest("INVALID_REQUEST",
                    "Idempotency-Key must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters", null);
        }
        if (scenario == null) {
            throw ApiException.badRequest("INVALID_REQUEST", "A payment scenario is required", null);
        }
        StartedAttempt started = transactions.startAttempt(customer, holdId, idempotencyKey, scenario);
        PaymentView attempt = started.created() ? charge(started.attempt()) : resolveIfDue(started.attempt());
        return outcome(attempt);
    }

    /** Current state of the caller's payment, resolved first if its outcome is overdue. */
    public PaymentOutcome getPayment(AuthenticatedUser customer, UUID paymentId) {
        return outcome(resolveIfDue(transactions.getOwnedPayment(customer, paymentId)));
    }

    private PaymentView charge(PaymentView attempt) {
        ProviderChargeStatus status;
        try {
            status = provider.charge(attempt.idempotencyKey(), attempt.amount(), attempt.scenario());
        } catch (RuntimeException ex) {
            // Timeout or error: money may or may not have been taken.
            log.warn("Charge for payment {} has no known outcome: {}", attempt.id(), ex.toString());
            return transactions.recordUnknown(attempt.id());
        }
        return applyProviderStatus(attempt, status);
    }

    /**
     * UNKNOWN attempts, and PENDING attempts older than the payment timeout (their charge can no
     * longer be in flight), are resolved from the provider's ledger.
     */
    private PaymentView resolveIfDue(PaymentView attempt) {
        boolean due = attempt.status() == PaymentStatus.UNKNOWN
                || (attempt.status() == PaymentStatus.PENDING
                        && !clock.instant().isBefore(attempt.updatedAt().plus(paymentTimeout)));
        if (!due) {
            return attempt;
        }
        ProviderChargeStatus status;
        try {
            status = provider.status(attempt.idempotencyKey());
        } catch (RuntimeException ex) {
            log.warn("Status query for payment {} failed; still unresolved: {}", attempt.id(), ex.toString());
            return attempt;
        }
        return applyProviderStatus(attempt, status);
    }

    private PaymentView applyProviderStatus(PaymentView attempt, ProviderChargeStatus status) {
        return switch (status) {
            case CAPTURED -> completeCapture(attempt);
            case DECLINED -> transactions.recordDeclined(attempt.id(), DeclineReason.PROVIDER_DECLINED);
            // The ledger affirmatively has no charge for this key: it was never received.
            case NOT_FOUND -> transactions.recordDeclined(attempt.id(), DeclineReason.NOT_RECEIVED);
            case VOIDED -> transactions.recordVoided(attempt.id());
        };
    }

    private PaymentView completeCapture(PaymentView attempt) {
        Confirmation confirmation;
        try {
            confirmation = transactions.confirmCaptured(attempt.id());
        } catch (RuntimeException ex) {
            // Rolled back as a whole; the attempt keeps its in-flight status and is resolved later.
            log.error("Confirming captured payment {} failed; left for resolution", attempt.id(), ex);
            return attempt;
        }
        if (!confirmation.mustVoid()) {
            return confirmation.attempt();
        }
        try {
            if (provider.voidCharge(attempt.idempotencyKey()) == ProviderChargeStatus.VOIDED) {
                return transactions.recordVoided(attempt.id());
            }
            log.error("Void of payment {} was not acknowledged", attempt.id());
        } catch (RuntimeException ex) {
            log.error("Void of payment {} failed", attempt.id(), ex);
        }
        // Captured and not voided: never reported as failed. A later resolution voids it again.
        return transactions.recordUnknown(attempt.id());
    }

    private PaymentOutcome outcome(PaymentView attempt) {
        BookingConfirmation booking = attempt.status() == PaymentStatus.SUCCEEDED
                ? bookingService.getForPayment(attempt.id()) : null;
        return new PaymentOutcome(attempt, booking);
    }

    /** {@code booking} is present exactly when the payment SUCCEEDED. */
    public record PaymentOutcome(PaymentView payment, BookingConfirmation booking) {
    }
}
