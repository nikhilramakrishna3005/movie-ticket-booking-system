package com.moviebooking.payment;

public enum DeclineReason {
    /** The provider explicitly declined the charge. */
    PROVIDER_DECLINED,
    /** The provider's ledger has no charge for the key: it never received the request. */
    NOT_RECEIVED
}
