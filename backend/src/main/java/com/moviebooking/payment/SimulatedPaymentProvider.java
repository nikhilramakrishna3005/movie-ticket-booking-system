package com.moviebooking.payment;

import java.math.BigDecimal;
import java.time.Clock;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stand-in for an external payment provider. It keeps its own ledger, written in its own short
 * transaction, and is idempotent per key: a repeated charge returns the recorded result without
 * charging again. Outcomes are deterministic (chosen by {@link PaymentScenario}); there are no
 * real delays.
 *
 * <p>Like a real network call, it must never run inside the application's database transaction,
 * so every operation fails fast if one is active.
 */
@Component
public class SimulatedPaymentProvider {

    private final SimulatedProviderChargeRepository ledger;
    private final TransactionTemplate ledgerTransaction;
    private final Clock clock;

    public SimulatedPaymentProvider(SimulatedProviderChargeRepository ledger,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.ledger = ledger;
        this.ledgerTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Returns CAPTURED or DECLINED, or replays the recorded status for a known key. For TIMEOUT
     * the capture is recorded first and then {@link ProviderTimeoutException} is thrown, modelling
     * "money taken, caller does not know".
     */
    public ProviderChargeStatus charge(String idempotencyKey, BigDecimal amount, PaymentScenario scenario) {
        requireNoTransaction("charge");
        ProviderChargeStatus recorded = ledgerTransaction.execute(tx -> ledger.findById(idempotencyKey)
                .map(SimulatedProviderCharge::getStatus)
                .orElse(null));
        if (recorded != null) {
            return recorded;
        }
        ProviderChargeStatus outcome = scenario == PaymentScenario.DECLINE
                ? ProviderChargeStatus.DECLINED : ProviderChargeStatus.CAPTURED;
        try {
            ledgerTransaction.executeWithoutResult(tx -> ledger.saveAndFlush(
                    new SimulatedProviderCharge(idempotencyKey, amount, outcome, clock.instant())));
        } catch (DataIntegrityViolationException concurrentChargeWithSameKey) {
            return status(idempotencyKey);
        }
        if (scenario == PaymentScenario.TIMEOUT) {
            throw new ProviderTimeoutException(idempotencyKey);
        }
        return outcome;
    }

    public ProviderChargeStatus status(String idempotencyKey) {
        requireNoTransaction("status");
        return ledgerTransaction.execute(tx -> ledger.findById(idempotencyKey)
                .map(SimulatedProviderCharge::getStatus)
                .orElse(ProviderChargeStatus.NOT_FOUND));
    }

    /** Voids a captured charge (simulated refund). Idempotent; returns the resulting ledger status. */
    public ProviderChargeStatus voidCharge(String idempotencyKey) {
        requireNoTransaction("void");
        return ledgerTransaction.execute(tx -> ledger.findById(idempotencyKey)
                .map(charge -> {
                    if (charge.getStatus() == ProviderChargeStatus.CAPTURED) {
                        charge.voidCapture(clock.instant());
                    }
                    return charge.getStatus();
                })
                .orElse(ProviderChargeStatus.NOT_FOUND));
    }

    private static void requireNoTransaction(String operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Payment provider " + operation
                    + " called inside a database transaction; provider calls must happen outside transactions");
        }
    }
}
