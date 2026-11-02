package com.moviebooking.payment;

/** Payment attempt states (docs/architecture.md §6.2). PENDING and UNKNOWN are "in flight". */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    DECLINED,
    UNKNOWN,
    VOIDED;

    public boolean isInFlight() {
        return this == PENDING || this == UNKNOWN;
    }
}
