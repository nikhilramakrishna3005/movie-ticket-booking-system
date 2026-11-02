package com.moviebooking.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.config.ApiException;
import com.moviebooking.config.ResourceNotFoundException;
import com.moviebooking.payment.PaymentAttemptService;
import com.moviebooking.security.AccessPolicy;
import com.moviebooking.security.AuthenticatedUser;
import com.moviebooking.theater.Showtime;
import com.moviebooking.theater.TheaterService;

/**
 * Creates and releases seat holds (docs/architecture.md §5). Every transaction that locks both
 * kinds of row locks the seat_hold row first, then show_seat rows in ascending seat_id order, and
 * waits at most {@code booking.lock-timeout} for any lock.
 */
@Service
public class HoldService {

    public static final int MAX_SEATS_PER_HOLD = 10;

    static final String ONE_ACTIVE_HOLD_INDEX = "seat_hold_one_active_per_customer_showtime_uk";

    private final SeatHoldRepository holds;
    private final ShowSeatRepository showSeats;
    private final TheaterService theaterService;
    private final PaymentAttemptService payments;
    private final Clock clock;
    private final Duration holdTtl;
    private final String lockTimeout;

    public HoldService(SeatHoldRepository holds, ShowSeatRepository showSeats, TheaterService theaterService,
            PaymentAttemptService payments, Clock clock, @Value("${booking.hold-ttl}") Duration holdTtl,
            @Value("${booking.lock-timeout}") Duration lockTimeout) {
        this.holds = holds;
        this.showSeats = showSeats;
        this.theaterService = theaterService;
        this.payments = payments;
        this.clock = clock;
        this.holdTtl = holdTtl;
        this.lockTimeout = lockTimeout.toMillis() + "ms";
    }

    /**
     * Holds all requested seats or none. Failures roll back the whole transaction, including the
     * expiry of a stale hold, so a rejected request changes nothing.
     */
    @Transactional
    public HoldResult createHold(AuthenticatedUser customer, long showtimeId, List<Long> seatIds) {
        Set<Long> requested = validateSelection(seatIds);
        // timestamptz stores microseconds; truncating keeps the returned expiry identical to the stored one.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Showtime showtime = theaterService.getShowtime(showtimeId);
        if (!showtime.isOpenForHoldsAt(now)) {
            throw ApiException.conflict("SHOWTIME_CLOSED", "The showtime has started or was cancelled",
                    Map.of("showtimeId", showtimeId));
        }

        showSeats.setLocalLockTimeout(lockTimeout);

        // Lock order step 1: the customer's ACTIVE hold row for this showtime, if any.
        UUID staleHoldId = null;
        Optional<SeatHold> existing = holds.lockActiveHold(customer.id(), showtimeId);
        if (existing.isPresent()) {
            SeatHold active = existing.get();
            if (!active.isExpiredAt(now)) {
                throw ApiException.conflict("ACTIVE_HOLD_EXISTS",
                        "You already have an active hold for this showtime; release it or let it expire",
                        Map.of("holdId", active.getId(), "expiresAt", active.getExpiresAt()));
            }
            // The partial unique index admits the new ACTIVE hold only after this UPDATE reaches the
            // database. Hibernate orders INSERTs before UPDATEs within one flush, so flush explicitly
            // instead of relying on the auto-flush that the next native query happens to trigger.
            active.expire();
            holds.saveAndFlush(active);
            staleHoldId = active.getId();
        }

        // Lock order step 2: seat rows in ascending seat_id order.
        List<ShowSeat> locked = staleHoldId == null
                ? showSeats.lockSeats(showtimeId, requested)
                : showSeats.lockSeatsAndStaleHold(showtimeId, requested, staleHoldId);

        Map<Long, ShowSeat> requestedSeats = new LinkedHashMap<>();
        for (ShowSeat seat : locked) {
            if (staleHoldId != null && seat.isHeldBy(staleHoldId)) {
                seat.release();
            }
            if (requested.contains(seat.getSeatId())) {
                requestedSeats.put(seat.getSeatId(), seat);
            }
        }

        Set<Long> unknown = new TreeSet<>(requested);
        unknown.removeAll(requestedSeats.keySet());
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("INVALID_SEATS", "Some seats do not exist for this showtime",
                    Map.of("seatIds", List.copyOf(unknown)));
        }

        List<Long> unavailable = requestedSeats.values().stream()
                .filter(seat -> seat.effectiveStatusAt(now) != ShowSeatStatus.AVAILABLE)
                .map(ShowSeat::getSeatId)
                .toList();
        if (!unavailable.isEmpty()) {
            throw ApiException.conflict("SEATS_UNAVAILABLE", "Some seats are already held or booked",
                    Map.of("seatIds", unavailable));
        }

        SeatHold hold = SeatHold.activate(customer.id(), showtimeId, now, now.plus(holdTtl));
        insert(hold);
        requestedSeats.values().forEach(seat -> seat.hold(hold.getId(), hold.getExpiresAt()));

        return new HoldResult(hold.getId(), showtimeId, List.copyOf(requestedSeats.keySet()), hold.getStatus(),
                hold.getExpiresAt());
    }

    /**
     * Releases the caller's hold. Holds of other customers are reported as not found. Releasing a
     * hold that is already released or expired changes nothing.
     */
    @Transactional
    public void releaseHold(AuthenticatedUser customer, UUID holdId) {
        Instant now = clock.instant();
        showSeats.setLocalLockTimeout(lockTimeout);

        SeatHold hold = holds.lockById(holdId).orElseThrow(ResourceNotFoundException::new);
        AccessPolicy.requireOwner(customer, hold.getCustomerId());

        switch (hold.getStatus()) {
            case RELEASED, EXPIRED -> {
            }
            case CONVERTED -> throw ApiException.conflict("HOLD_CONVERTED",
                    "The hold was already converted into a booking", Map.of("holdId", holdId));
            case ACTIVE -> {
                // Checked under the hold lock, which every payment transaction also takes first.
                payments.findInFlight(holdId).ifPresent(inFlight -> {
                    throw ApiException.conflict("PAYMENT_IN_PROGRESS",
                            "The hold cannot be released while its payment is in progress or unresolved",
                            Map.of("paymentId", inFlight.id()));
                });
                // Seats whose expired claim was taken over by another hold no longer point here,
                // so only rows still owned by this hold are reset.
                showSeats.lockHeldBy(hold.getShowtimeId(), holdId).forEach(ShowSeat::release);
                if (hold.isExpiredAt(now)) {
                    hold.expire();
                } else {
                    hold.release();
                }
            }
        }
    }

    private static Set<Long> validateSelection(List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty() || seatIds.size() > MAX_SEATS_PER_HOLD) {
            throw ApiException.badRequest("INVALID_REQUEST",
                    "Select between 1 and " + MAX_SEATS_PER_HOLD + " seats", null);
        }
        Set<Long> unique = new HashSet<>();
        List<Long> duplicates = new ArrayList<>();
        for (Long seatId : seatIds) {
            if (seatId == null) {
                throw ApiException.badRequest("INVALID_REQUEST", "Seat ids must not be null", null);
            }
            if (!unique.add(seatId)) {
                duplicates.add(seatId);
            }
        }
        if (!duplicates.isEmpty()) {
            // Rejected rather than de-duplicated so client and server agree on what was requested.
            throw ApiException.badRequest("INVALID_REQUEST", "Seat ids must not repeat",
                    Map.of("duplicateSeatIds", duplicates.stream().distinct().sorted().toList()));
        }
        return unique;
    }

    /**
     * A concurrent request by the same customer can pass the lock step above before either hold
     * is committed; the partial unique index then rejects the second insert.
     */
    private void insert(SeatHold hold) {
        try {
            holds.saveAndFlush(hold);
        } catch (DataIntegrityViolationException ex) {
            if (ex.getCause() instanceof ConstraintViolationException violation
                    && ONE_ACTIVE_HOLD_INDEX.equals(violation.getConstraintName())) {
                throw ApiException.conflict("ACTIVE_HOLD_EXISTS",
                        "You already have an active hold for this showtime; release it or let it expire", null);
            }
            throw ex;
        }
    }

    public record HoldResult(UUID holdId, long showtimeId, List<Long> seatIds, HoldStatus status,
            Instant expiresAt) {
    }
}
