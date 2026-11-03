package com.moviebooking.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.config.ApiException;
import com.moviebooking.payment.PaymentAttemptService;
import com.moviebooking.theater.CancelledShowtime;
import com.moviebooking.theater.Showtime;
import com.moviebooking.theater.ShowtimeStatus;
import com.moviebooking.theater.TheaterService;

/**
 * Cancels a showtime without deleting inventory or bookings (docs/architecture.md §5.1, §8.1).
 *
 * <p>Policy: refuse if any seat is BOOKED, if any payment for the show is PENDING or UNKNOWN, or
 * if any hold is still effectively active. Expired ACTIVE rows are marked EXPIRED and their seats
 * released in this transaction so they cannot convert afterwards. Coordinated invalidation of
 * live holds is not done: a customer mid-checkout keeps the hold, and an in-flight simulated
 * capture is never voided by cancellation.
 *
 * <p>Lock order: showtime row, then all {@code seat_hold} rows of the show ordered by id, then
 * any {@code show_seat} rows still HELD, ordered by seat_id. Hold creation takes the showtime
 * lock first, so a concurrent hold waits and then sees {@code CANCELLED}.
 */
@Service
public class ShowtimeCancellationService {

    private final TheaterService theaterService;
    private final SeatHoldRepository holds;
    private final ShowSeatRepository showSeats;
    private final PaymentAttemptService payments;
    private final Clock clock;
    private final String lockTimeout;

    public ShowtimeCancellationService(TheaterService theaterService, SeatHoldRepository holds,
            ShowSeatRepository showSeats, PaymentAttemptService payments, Clock clock,
            @Value("${booking.lock-timeout}") Duration lockTimeout) {
        this.theaterService = theaterService;
        this.holds = holds;
        this.showSeats = showSeats;
        this.payments = payments;
        this.clock = clock;
        this.lockTimeout = lockTimeout.toMillis() + "ms";
    }

    @Transactional
    public CancelledShowtime cancel(long showtimeId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        showSeats.setLocalLockTimeout(lockTimeout);

        Showtime showtime = theaterService.lockShowtime(showtimeId);
        if (showtime.getStatus() == ShowtimeStatus.CANCELLED) {
            return theaterService.cancelledView(showtime);
        }

        List<SeatHold> lockedHolds = holds.lockAllForShowtime(showtimeId);

        long booked = showSeats.countByShowtimeIdAndStatus(showtimeId, ShowSeatStatus.BOOKED);
        if (booked > 0) {
            throw ApiException.conflict("SHOWTIME_HAS_BOOKINGS",
                    "This showtime has confirmed bookings and cannot be cancelled",
                    Map.of("bookedSeats", booked));
        }

        long inFlight = payments.countInFlightForShowtime(showtimeId);
        if (inFlight > 0) {
            throw ApiException.conflict("SHOWTIME_HAS_UNRESOLVED_PAYMENTS",
                    "A payment for this showtime is still in progress or unresolved; it must settle before cancellation",
                    Map.of("inFlightPayments", inFlight));
        }

        List<SeatHold> live = lockedHolds.stream()
                .filter(hold -> hold.getStatus() == HoldStatus.ACTIVE && !hold.isExpiredAt(now))
                .toList();
        if (!live.isEmpty()) {
            throw ApiException.conflict("SHOWTIME_HAS_ACTIVE_HOLDS",
                    "Active seat holds must be released or expire before this showtime can be cancelled",
                    Map.of("activeHolds", live.size()));
        }

        List<SeatHold> stale = lockedHolds.stream()
                .filter(hold -> hold.getStatus() == HoldStatus.ACTIVE && hold.isExpiredAt(now))
                .toList();
        if (!stale.isEmpty()) {
            showSeats.lockHeldOnShowtime(showtimeId).forEach(ShowSeat::release);
            stale.forEach(SeatHold::expire);
        }

        theaterService.markCancelled(showtime);
        return theaterService.cancelledView(showtime);
    }
}
