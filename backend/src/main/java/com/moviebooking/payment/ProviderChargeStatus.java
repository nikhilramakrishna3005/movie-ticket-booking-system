package com.moviebooking.payment;

/** What the simulated provider's ledger says about an idempotency key. */
public enum ProviderChargeStatus {
    CAPTURED,
    DECLINED,
    VOIDED,
    /** No charge exists for the key: the provider never received it. */
    NOT_FOUND
}
