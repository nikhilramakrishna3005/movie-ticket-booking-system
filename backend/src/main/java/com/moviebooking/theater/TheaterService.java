package com.moviebooking.theater;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.config.ResourceNotFoundException;

/** Read access to showtimes and physical seats for other packages. */
@Service
@Transactional(readOnly = true)
public class TheaterService {

    private final ShowtimeRepository showtimes;
    private final SeatRepository seats;

    public TheaterService(ShowtimeRepository showtimes, SeatRepository seats) {
        this.showtimes = showtimes;
        this.seats = seats;
    }

    public Showtime getShowtime(long showtimeId) {
        return showtimes.findById(showtimeId).orElseThrow(ResourceNotFoundException::new);
    }

    public ShowtimeDetails getShowtimeDetails(long showtimeId) {
        Showtime showtime = getShowtime(showtimeId);
        ShowtimeRepository.VenueNames venue = showtimes.findVenueNames(showtimeId)
                .orElseThrow(ResourceNotFoundException::new);
        return new ShowtimeDetails(showtimeId, showtime.getMovieId(), showtime.getScreenId(), showtime.getStartsAt(),
                venue.getTheaterName(), venue.getScreenName(), venue.getCityName() + ", " + venue.getStateCode());
    }

    /** Seats of a screen in display order (row, then seat number). */
    public List<Seat> getSeats(long screenId) {
        return seats.findByScreenIdOrderByRowLabelAscSeatNumberAsc(screenId);
    }
}
