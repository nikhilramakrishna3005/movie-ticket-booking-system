package com.moviebooking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Pure expiry and state rules of holds and seat inventory, without a database. */
class HoldRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final Instant EXPIRES = NOW.plus(Duration.ofMinutes(8));

    @Test
    void heldSeatIsUnavailableUntilTheExactExpiryInstant() {
        ShowSeat seat = availableSeat();
        seat.hold(UUID.randomUUID(), EXPIRES);

        assertThat(seat.effectiveStatusAt(EXPIRES.minusNanos(1000))).isEqualTo(ShowSeatStatus.HELD);
        assertThat(seat.effectiveStatusAt(EXPIRES)).isEqualTo(ShowSeatStatus.AVAILABLE);
        assertThat(seat.effectiveStatusAt(EXPIRES.plusSeconds(1))).isEqualTo(ShowSeatStatus.AVAILABLE);
    }

    @Test
    void releasedSeatHasNoHoldReference() {
        UUID holdId = UUID.randomUUID();
        ShowSeat seat = availableSeat();
        seat.hold(holdId, EXPIRES);
        assertThat(seat.isHeldBy(holdId)).isTrue();
        assertThat(seat.isHeldBy(UUID.randomUUID())).isFalse();

        seat.release();

        assertThat(seat.isHeldBy(holdId)).isFalse();
        assertThat(seat.effectiveStatusAt(NOW)).isEqualTo(ShowSeatStatus.AVAILABLE);
    }

    @Test
    void holdExpiresAtItsExpiryInstant() {
        SeatHold hold = SeatHold.activate(1L, 2L, NOW, EXPIRES);

        assertThat(hold.isNew()).isTrue();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.isExpiredAt(EXPIRES.minusNanos(1000))).isFalse();
        assertThat(hold.isExpiredAt(EXPIRES)).isTrue();
    }

    @Test
    void onlyActiveHoldsCanBeReleasedOrExpired() {
        SeatHold released = SeatHold.activate(1L, 2L, NOW, EXPIRES);
        released.release();
        SeatHold expired = SeatHold.activate(1L, 2L, NOW, EXPIRES);
        expired.expire();

        assertThat(released.getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(expired.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        assertThatThrownBy(released::expire).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(expired::release).isInstanceOf(IllegalStateException.class);
    }

    private static ShowSeat availableSeat() {
        ShowSeat seat = new ShowSeat();
        seat.release();
        return seat;
    }
}
