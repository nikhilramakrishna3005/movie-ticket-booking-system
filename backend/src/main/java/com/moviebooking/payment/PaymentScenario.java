package com.moviebooking.payment;

/** Outcome the simulated provider produces for a new charge, chosen by the client. */
public enum PaymentScenario {
    SUCCESS,
    DECLINE,
    /** The charge is captured, but the caller sees a timeout and does not learn the outcome. */
    TIMEOUT
}
