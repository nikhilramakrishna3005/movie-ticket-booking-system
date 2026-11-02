package com.moviebooking.payment;

/** The caller did not get the provider's answer in time; the charge may or may not exist. */
public class ProviderTimeoutException extends RuntimeException {

    public ProviderTimeoutException(String idempotencyKey) {
        super("No response from the payment provider for key " + idempotencyKey);
    }
}
