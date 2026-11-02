package com.moviebooking.booking;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.theater.SeatCategory;
import com.moviebooking.theater.Showtime;
import com.moviebooking.theater.ShowtimeStatus;
import com.moviebooking.theater.TheaterService;

/** Builds the seat map of a showtime with each seat's effective status at the current instant. */
@Service
public class SeatMapService {

    private final ShowSeatRepository showSeats;
    private final TheaterService theaterService;
    private final Clock clock;

    public SeatMapService(ShowSeatRepository showSeats, TheaterService theaterService, Clock clock) {
        this.showSeats = showSeats;
        this.theaterService = theaterService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SeatMap getSeatMap(long showtimeId) {
        Instant now = clock.instant();
        Showtime showtime = theaterService.getShowtime(showtimeId);
        Map<Long, ShowSeat> inventory = showSeats.findByShowtimeId(showtimeId).stream()
                .collect(Collectors.toMap(ShowSeat::getSeatId, Function.identity()));

        List<SeatMapEntry> seats = theaterService.getSeats(showtime.getScreenId()).stream()
                .filter(seat -> inventory.containsKey(seat.getId()))
                .map(seat -> {
                    ShowSeat showSeat = inventory.get(seat.getId());
                    return new SeatMapEntry(seat.getId(), seat.getRowLabel(), seat.getSeatNumber(),
                            seat.getCategory(), showSeat.getPrice(), showSeat.effectiveStatusAt(now));
                })
                .toList();
        return new SeatMap(showtimeId, showtime.getStatus(), showtime.getStartsAt(), seats);
    }

    public record SeatMap(long showtimeId, ShowtimeStatus showtimeStatus, Instant startsAt,
            List<SeatMapEntry> seats) {
    }

    public record SeatMapEntry(long seatId, String rowLabel, int seatNumber, SeatCategory category,
            BigDecimal price, ShowSeatStatus status) {
    }
}
