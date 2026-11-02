package com.moviebooking.payment;

import java.math.BigDecimal;
import java.time.Instant;

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

/** A row of the simulated provider's own ledger; status is CAPTURED, DECLINED or VOIDED. */
@Entity
@Table(name = "simulated_provider_charge")
class SimulatedProviderCharge implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProviderChargeStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected SimulatedProviderCharge() {
    }

    SimulatedProviderCharge(String idempotencyKey, BigDecimal amount, ProviderChargeStatus status, Instant now) {
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
        this.isNew = true;
    }

    @Override
    public String getId() {
        return idempotencyKey;
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

    ProviderChargeStatus getStatus() {
        return status;
    }

    void voidCapture(Instant now) {
        status = ProviderChargeStatus.VOIDED;
        updatedAt = now;
    }
}
