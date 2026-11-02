package com.moviebooking.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable snapshot of a payment attempt, safe to pass between transactions. */
public record PaymentView(UUID id, UUID holdId, String idempotencyKey, PaymentScenario scenario, BigDecimal amount,
        PaymentStatus status, DeclineReason failureReason, Instant updatedAt) {

    static PaymentView of(PaymentAttempt attempt) {
        return new PaymentView(attempt.getId(), attempt.getHoldId(), attempt.getIdempotencyKey(),
                attempt.getScenario(), attempt.getAmount(), attempt.getStatus(), attempt.getFailureReason(),
                attempt.getUpdatedAt());
    }
}
