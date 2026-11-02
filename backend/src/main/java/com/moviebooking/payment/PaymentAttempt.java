package com.moviebooking.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * One payment action for a hold, identified by the client's idempotency key. Transition methods
 * enforce the state machine in docs/architecture.md §6.3; callers hold the seat_hold row lock.
 */
@Entity
@Table(name = "payment_attempt")
public class PaymentAttempt implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "hold_id", nullable = false)
    private UUID holdId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentScenario scenario;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason")
    private DeclineReason failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // The id is assigned in Java, so Spring Data cannot tell new from existing by a null id.
    @Transient
    private boolean isNew;

    protected PaymentAttempt() {
    }

    static PaymentAttempt pending(UUID holdId, String idempotencyKey, PaymentScenario scenario, BigDecimal amount,
            Instant now) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = UUID.randomUUID();
        attempt.holdId = holdId;
        attempt.idempotencyKey = idempotencyKey;
        attempt.scenario = scenario;
        attempt.amount = amount;
        attempt.status = PaymentStatus.PENDING;
        attempt.createdAt = now;
        attempt.updatedAt = now;
        attempt.isNew = true;
        return attempt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        isNew = false;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public PaymentScenario getScenario() {
        return scenario;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public DeclineReason getFailureReason() {
        return failureReason;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void succeed(Instant now) {
        transition(EnumSet.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN), PaymentStatus.SUCCEEDED, now);
    }

    void decline(DeclineReason reason, Instant now) {
        transition(EnumSet.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN), PaymentStatus.DECLINED, now);
        failureReason = reason;
    }

    /** Recorded only after the provider acknowledged the void. */
    void voided(Instant now) {
        transition(EnumSet.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN), PaymentStatus.VOIDED, now);
    }

    void unknown(Instant now) {
        transition(EnumSet.of(PaymentStatus.PENDING), PaymentStatus.UNKNOWN, now);
    }

    private void transition(Set<PaymentStatus> allowedFrom, PaymentStatus to, Instant now) {
        if (!allowedFrom.contains(status)) {
            throw new IllegalStateException("Payment attempt " + id + " cannot go from " + status + " to " + to);
        }
        status = to;
        updatedAt = now;
    }
}
