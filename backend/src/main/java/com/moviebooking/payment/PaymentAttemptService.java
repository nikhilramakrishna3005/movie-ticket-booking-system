package com.moviebooking.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.config.ApiException;
import com.moviebooking.config.ResourceNotFoundException;

/**
 * Reads and changes payment attempts. Every method joins the caller's transaction (MANDATORY):
 * the booking package calls these while it holds the seat_hold row lock, which serializes all
 * changes to the attempts of that hold.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentAttemptService {

    static final String IDEMPOTENCY_KEY_CONSTRAINT = "payment_attempt_idempotency_key_uk";
    static final String ONE_IN_FLIGHT_INDEX = "payment_attempt_one_in_flight_per_hold_uk";

    private final PaymentAttemptRepository attempts;

    public PaymentAttemptService(PaymentAttemptRepository attempts) {
        this.attempts = attempts;
    }

    public Optional<PaymentView> findByIdempotencyKey(String key) {
        return attempts.findByIdempotencyKey(key).map(PaymentView::of);
    }

    public Optional<PaymentView> findInFlight(UUID holdId) {
        return attempts.findFirstByHoldIdAndStatusIn(holdId, EnumSet.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN))
                .map(PaymentView::of);
    }

    public long countForHold(UUID holdId) {
        return attempts.countByHoldId(holdId);
    }

    public Optional<UUID> findHoldId(UUID attemptId) {
        return attempts.findHoldIdById(attemptId);
    }

    public PaymentView get(UUID attemptId) {
        return PaymentView.of(load(attemptId));
    }

    /** Inserts and flushes a PENDING attempt so it is durable once the caller commits. */
    public PaymentView createPending(UUID holdId, String key, PaymentScenario scenario, BigDecimal amount,
            Instant now) {
        try {
            return PaymentView.of(attempts.saveAndFlush(PaymentAttempt.pending(holdId, key, scenario, amount, now)));
        } catch (DataIntegrityViolationException ex) {
            String constraint = ex.getCause() instanceof ConstraintViolationException violation
                    ? violation.getConstraintName() : null;
            if (IDEMPOTENCY_KEY_CONSTRAINT.equals(constraint)) {
                // Same key used concurrently for a different hold; the same hold is serialized by its lock.
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "This idempotency key was already used for a different payment request", null);
            }
            if (ONE_IN_FLIGHT_INDEX.equals(constraint)) {
                throw ApiException.conflict("PAYMENT_IN_PROGRESS", "A payment for this hold is already in progress",
                        null);
            }
            throw ex;
        }
    }

    public PaymentView markSucceeded(UUID attemptId, Instant now) {
        PaymentAttempt attempt = load(attemptId);
        attempt.succeed(now);
        return PaymentView.of(attempt);
    }

    public PaymentView markDeclined(UUID attemptId, DeclineReason reason, Instant now) {
        PaymentAttempt attempt = load(attemptId);
        attempt.decline(reason, now);
        return PaymentView.of(attempt);
    }

    public PaymentView markVoided(UUID attemptId, Instant now) {
        PaymentAttempt attempt = load(attemptId);
        attempt.voided(now);
        return PaymentView.of(attempt);
    }

    public PaymentView markUnknown(UUID attemptId, Instant now) {
        PaymentAttempt attempt = load(attemptId);
        attempt.unknown(now);
        return PaymentView.of(attempt);
    }

    private PaymentAttempt load(UUID attemptId) {
        return attempts.findById(attemptId).orElseThrow(ResourceNotFoundException::new);
    }
}
