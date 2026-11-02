package com.moviebooking.booking;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.config.ApiException;
import com.moviebooking.config.ResourceNotFoundException;
import com.moviebooking.payment.DeclineReason;
import com.moviebooking.payment.PaymentAttemptService;
import com.moviebooking.payment.PaymentScenario;
import com.moviebooking.payment.PaymentStatus;
import com.moviebooking.payment.PaymentView;
import com.moviebooking.security.AccessPolicy;
import com.moviebooking.security.AuthenticatedUser;
import com.moviebooking.theater.ShowtimeStatus;
import com.moviebooking.theater.TheaterService;

/**
 * The short database transactions of the checkout flow (docs/architecture.md §6.4–§6.8). They live
 * in their own bean so that {@link CheckoutService} calls them through the Spring proxy (no
 * self-invocation) and can call the payment provider between them, outside any transaction.
 *
 * <p>Every method that changes a payment attempt first locks the attempt's seat_hold row, then any
 * show_seat rows in seat_id order. The hold lock serializes all payment activity for one hold.
 */
@Service
public class CheckoutTransactions {

    static final int MAX_ATTEMPTS_PER_HOLD = 3;

    private final SeatHoldRepository holds;
    private final ShowSeatRepository showSeats;
    private final BookingRepository bookings;
    private final BookingSeatRepository bookingSeats;
    private final OutboxEventRepository outbox;
    private final PaymentAttemptService payments;
    private final TheaterService theaterService;
    private final Clock clock;
    private final Duration minimumRemaining;
    private final String lockTimeout;

    public CheckoutTransactions(SeatHoldRepository holds, ShowSeatRepository showSeats, BookingRepository bookings,
            BookingSeatRepository bookingSeats, OutboxEventRepository outbox, PaymentAttemptService payments,
            TheaterService theaterService, Clock clock,
            @Value("${booking.payment-min-remaining}") Duration minimumRemaining,
            @Value("${booking.lock-timeout}") Duration lockTimeout) {
        this.holds = holds;
        this.showSeats = showSeats;
        this.bookings = bookings;
        this.bookingSeats = bookingSeats;
        this.outbox = outbox;
        this.payments = payments;
        this.theaterService = theaterService;
        this.clock = clock;
        this.minimumRemaining = minimumRemaining;
        this.lockTimeout = lockTimeout.toMillis() + "ms";
    }

    /**
     * TX2: replays a known idempotency key, or validates the hold and commits a PENDING attempt.
     * The commit happens before the provider is called, so a durable record always exists.
     */
    @Transactional
    public StartedAttempt startAttempt(AuthenticatedUser customer, UUID holdId, String idempotencyKey,
            PaymentScenario scenario) {
        Instant now = now();
        showSeats.setLocalLockTimeout(lockTimeout);
        SeatHold hold = holds.lockById(holdId).orElseThrow(ResourceNotFoundException::new);
        AccessPolicy.requireOwner(customer, hold.getCustomerId());

        // Checked under the hold lock, so concurrent requests with the same key see each other.
        Optional<PaymentView> existing = payments.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            PaymentView attempt = existing.get();
            if (!attempt.holdId().equals(holdId) || attempt.scenario() != scenario) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "This idempotency key was already used for a different payment request", null);
            }
            return new StartedAttempt(attempt, false);
        }

        requirePayable(hold, now);
        payments.findInFlight(holdId).ifPresent(inFlight -> {
            throw ApiException.conflict("PAYMENT_IN_PROGRESS", "A payment for this hold is already in progress",
                    Map.of("paymentId", inFlight.id()));
        });
        if (payments.countForHold(holdId) >= MAX_ATTEMPTS_PER_HOLD) {
            throw ApiException.conflict("TOO_MANY_ATTEMPTS",
                    "This hold has reached the limit of " + MAX_ATTEMPTS_PER_HOLD + " payment attempts", null);
        }

        // While the hold is locked, ACTIVE and unexpired, no other transaction can change its seats.
        BigDecimal amount = total(showSeats.findByHoldIdOrderBySeatId(holdId));
        return new StartedAttempt(payments.createPending(holdId, idempotencyKey, scenario, amount, now), true);
    }

    /**
     * TX3, after the provider reported a capture: revalidates everything under lock and either
     * confirms the booking in one commit, or reports that the capture must be voided. An expired
     * hold is never converted, even if its seats are still free.
     */
    @Transactional
    public Confirmation confirmCaptured(UUID attemptId) {
        Instant now = now();
        SeatHold hold = lockHoldOf(attemptId);
        PaymentView attempt = payments.get(attemptId);
        if (!attempt.status().isInFlight()) {
            // Already settled by a concurrent confirm or resolve. Unless that produced the booking,
            // this capture has nothing to pay for and must be voided.
            return new Confirmation(attempt, attempt.status() != PaymentStatus.SUCCEEDED);
        }
        List<ShowSeat> seats = showSeats.lockHeldBy(hold.getShowtimeId(), hold.getId());

        if (!isConfirmable(hold, seats, attempt, now)) {
            if (hold.getStatus() == HoldStatus.ACTIVE && hold.isExpiredAt(now)) {
                seats.forEach(ShowSeat::release);
                hold.expire();
            }
            return new Confirmation(attempt, true);
        }

        Booking booking = bookings.saveAndFlush(Booking.confirm(hold, attemptId, attempt.amount(), now));
        for (ShowSeat seat : seats) {
            bookingSeats.save(new BookingSeat(booking.getId(), hold.getShowtimeId(), seat.getSeatId()));
            seat.book(booking.getId());
        }
        hold.convert();
        PaymentView succeeded = payments.markSucceeded(attemptId, now);
        outbox.save(OutboxEvent.bookingConfirmed(booking.getId(), eventPayload(booking, seats), now));
        return new Confirmation(succeeded, false);
    }

    @Transactional
    public PaymentView recordDeclined(UUID attemptId, DeclineReason reason) {
        lockHoldOf(attemptId);
        PaymentView attempt = payments.get(attemptId);
        return attempt.status().isInFlight() ? payments.markDeclined(attemptId, reason, now()) : attempt;
    }

    /** Only after the provider acknowledged the void (docs/architecture.md §6.3). */
    @Transactional
    public PaymentView recordVoided(UUID attemptId) {
        lockHoldOf(attemptId);
        PaymentView attempt = payments.get(attemptId);
        return attempt.status().isInFlight() ? payments.markVoided(attemptId, now()) : attempt;
    }

    /** The provider call failed or timed out: the outcome is unknown, never assumed declined. */
    @Transactional
    public PaymentView recordUnknown(UUID attemptId) {
        lockHoldOf(attemptId);
        PaymentView attempt = payments.get(attemptId);
        return attempt.status() == PaymentStatus.PENDING ? payments.markUnknown(attemptId, now()) : attempt;
    }

    /** Payments of other customers are reported as not found. */
    @Transactional(readOnly = true)
    public PaymentView getOwnedPayment(AuthenticatedUser customer, UUID attemptId) {
        PaymentView attempt = payments.get(attemptId);
        SeatHold hold = holds.findById(attempt.holdId()).orElseThrow(ResourceNotFoundException::new);
        AccessPolicy.requireOwner(customer, hold.getCustomerId());
        return attempt;
    }

    private void requirePayable(SeatHold hold, Instant now) {
        if (hold.getStatus() == HoldStatus.EXPIRED
                || (hold.getStatus() == HoldStatus.ACTIVE && hold.isExpiredAt(now))) {
            throw ApiException.conflict("HOLD_EXPIRED", "The hold has expired", Map.of("holdId", hold.getId()));
        }
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw ApiException.conflict("HOLD_NOT_ACTIVE", "The hold is no longer active",
                    Map.of("holdId", hold.getId(), "holdStatus", hold.getStatus()));
        }
        if (Duration.between(now, hold.getExpiresAt()).compareTo(minimumRemaining) < 0) {
            throw ApiException.conflict("HOLD_TOO_CLOSE_TO_EXPIRY",
                    "Too little time is left on the hold to complete a payment", Map.of("holdId", hold.getId()));
        }
    }

    /** "Hold valid" from docs/architecture.md §6.3, evaluated under lock at {@code now}. */
    private boolean isConfirmable(SeatHold hold, List<ShowSeat> seats, PaymentView attempt, Instant now) {
        return hold.getStatus() == HoldStatus.ACTIVE
                && !hold.isExpiredAt(now)
                && !seats.isEmpty()
                && total(seats).compareTo(attempt.amount()) == 0
                && theaterService.getShowtime(hold.getShowtimeId()).getStatus() == ShowtimeStatus.SCHEDULED;
    }

    /** Locks the attempt's hold without first loading the attempt, so the attempt is read after the lock. */
    private SeatHold lockHoldOf(UUID attemptId) {
        showSeats.setLocalLockTimeout(lockTimeout);
        UUID holdId = payments.findHoldId(attemptId).orElseThrow(ResourceNotFoundException::new);
        return holds.lockById(holdId).orElseThrow(ResourceNotFoundException::new);
    }

    private static BigDecimal total(List<ShowSeat> seats) {
        return seats.stream().map(ShowSeat::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<String, Object> eventPayload(Booking booking, List<ShowSeat> seats) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.getId().toString());
        payload.put("reference", booking.getReference());
        payload.put("customerId", booking.getCustomerId());
        payload.put("showtimeId", booking.getShowtimeId());
        payload.put("seatIds", seats.stream().map(ShowSeat::getSeatId).toList());
        payload.put("totalAmount", booking.getTotalAmount().toPlainString());
        payload.put("confirmedAt", booking.getCreatedAt().toString());
        return payload;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public record StartedAttempt(PaymentView attempt, boolean created) {
    }

    /** {@code mustVoid}: the provider captured money, but the hold could not be converted. */
    public record Confirmation(PaymentView attempt, boolean mustVoid) {
    }
}
