package com.moviebooking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.moviebooking.TestClockConfiguration;
import com.moviebooking.TestcontainersConfiguration;

/**
 * The reconciliation fence against a charge that is still running, at the provider level. The
 * test plays the other party by inserting its ledger row on its own connection and keeping that
 * transaction open; the real provider method then has already read "no entry" and must queue on
 * the ledger's primary key. The test waits for that queueing (pg_stat_activity), then commits.
 * Requires Docker.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class SimulatedProviderFenceIT {

    private static final BigDecimal AMOUNT = new BigDecimal("12.50");

    @Autowired
    private SimulatedPaymentProvider provider;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM simulated_provider_charge");
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        jdbcTemplate.update("DELETE FROM simulated_provider_charge");
    }

    /** A late charge whose read found nothing must not capture once the fence commits first. */
    @Test
    void chargeQueuedBehindAFenceIsRefused() throws Exception {
        try (Connection fence = dataSource.getConnection()) {
            fence.setAutoCommit(false);
            insertLedgerRow(fence, "DECLINED");

            Future<ProviderChargeStatus> charge = executor.submit(
                    () -> provider.charge("key-race", AMOUNT, PaymentScenario.SUCCESS));
            awaitInsertBlockedOnTheLedgerKey();
            fence.commit();

            assertThat(charge.get(30, TimeUnit.SECONDS)).isEqualTo(ProviderChargeStatus.DECLINED);
        }
        assertThat(ledgerStatus()).isEqualTo("DECLINED");
    }

    /** A fence whose read found nothing must report the capture that committed first. */
    @Test
    void fenceQueuedBehindACaptureReportsTheCapture() throws Exception {
        try (Connection capture = dataSource.getConnection()) {
            capture.setAutoCommit(false);
            insertLedgerRow(capture, "CAPTURED");

            Future<ProviderChargeStatus> fence = executor.submit(() -> provider.statusOrFence("key-race", AMOUNT));
            awaitInsertBlockedOnTheLedgerKey();
            capture.commit();

            assertThat(fence.get(30, TimeUnit.SECONDS)).isEqualTo(ProviderChargeStatus.CAPTURED);
        }
        assertThat(ledgerStatus()).isEqualTo("CAPTURED");
    }

    @Test
    void fenceOnAnUnknownKeyMakesEveryLaterChargeWithItDeclined() {
        assertThat(provider.statusOrFence("key-race", AMOUNT)).isEqualTo(ProviderChargeStatus.NOT_FOUND);

        assertThat(provider.charge("key-race", AMOUNT, PaymentScenario.SUCCESS))
                .isEqualTo(ProviderChargeStatus.DECLINED);
        assertThat(provider.statusOrFence("key-race", AMOUNT)).isEqualTo(ProviderChargeStatus.DECLINED);
        assertThat(ledgerStatus()).isEqualTo("DECLINED");
    }

    @Test
    void fenceDoesNotChangeAnExistingEntry() {
        assertThat(provider.charge("key-race", AMOUNT, PaymentScenario.SUCCESS))
                .isEqualTo(ProviderChargeStatus.CAPTURED);

        assertThat(provider.statusOrFence("key-race", AMOUNT)).isEqualTo(ProviderChargeStatus.CAPTURED);
        assertThat(ledgerStatus()).isEqualTo("CAPTURED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM simulated_provider_charge", Long.class))
                .isOne();
    }

    private static void insertLedgerRow(Connection connection, String status) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO simulated_provider_charge (idempotency_key, amount, status, created_at, updated_at)
                VALUES ('key-race', ?, ?, TIMESTAMPTZ '2026-11-02 12:00:00+00', TIMESTAMPTZ '2026-11-02 12:00:00+00')""")) {
            insert.setBigDecimal(1, AMOUNT);
            insert.setString(2, status);
            insert.executeUpdate();
        }
    }

    /**
     * Polls a condition with a deadline (no guessed sleep): the provider's INSERT is waiting for
     * the test's uncommitted row with the same key.
     */
    private void awaitInsertBlockedOnTheLedgerKey() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                  AND query ILIKE 'insert into simulated_provider_charge%'""", Long.class) == 0) {
            assertThat(System.nanoTime()).as("provider INSERT never queued on the key").isLessThan(deadline);
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    private String ledgerStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM simulated_provider_charge WHERE idempotency_key = 'key-race'", String.class);
    }
}
