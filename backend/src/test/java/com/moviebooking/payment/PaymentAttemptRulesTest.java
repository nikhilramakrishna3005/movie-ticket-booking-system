package com.moviebooking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** State machine of docs/architecture.md §6.3, without a database. */
class PaymentAttemptRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

    @Test
    void pendingAttemptCanReachEveryOutcome() {
        assertThat(pending().getStatus()).isEqualTo(PaymentStatus.PENDING);

        PaymentAttempt succeeded = pending();
        succeeded.succeed(NOW.plusSeconds(1));
        assertThat(succeeded.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(succeeded.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));

        PaymentAttempt declined = pending();
        declined.decline(DeclineReason.PROVIDER_DECLINED, NOW);
        assertThat(declined.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(declined.getFailureReason()).isEqualTo(DeclineReason.PROVIDER_DECLINED);

        PaymentAttempt voided = pending();
        voided.voided(NOW);
        assertThat(voided.getStatus()).isEqualTo(PaymentStatus.VOIDED);
    }

    @Test
    void unknownAttemptIsStillInFlightAndCanBeResolved() {
        PaymentAttempt attempt = pending();
        attempt.unknown(NOW);
        assertThat(attempt.getStatus().isInFlight()).isTrue();

        attempt.decline(DeclineReason.NOT_RECEIVED, NOW);
        assertThat(attempt.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(attempt.getFailureReason()).isEqualTo(DeclineReason.NOT_RECEIVED);
    }

    @Test
    void terminalOutcomesNeverChange() {
        PaymentAttempt attempt = pending();
        attempt.succeed(NOW);

        assertThatThrownBy(() -> attempt.voided(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> attempt.decline(DeclineReason.PROVIDER_DECLINED, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> attempt.unknown(NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(attempt.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(attempt.getStatus().isInFlight()).isFalse();
    }

    @Test
    void onlyAPendingAttemptCanBecomeUnknown() {
        PaymentAttempt attempt = pending();
        attempt.unknown(NOW);

        assertThatThrownBy(() -> attempt.unknown(NOW)).isInstanceOf(IllegalStateException.class);
    }

    private static PaymentAttempt pending() {
        return PaymentAttempt.pending(UUID.randomUUID(), "key", PaymentScenario.SUCCESS, BigDecimal.TEN, NOW);
    }
}
