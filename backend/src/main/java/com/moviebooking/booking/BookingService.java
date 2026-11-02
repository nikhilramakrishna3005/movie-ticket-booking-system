package com.moviebooking.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.catalog.CatalogService;
import com.moviebooking.config.ResourceNotFoundException;
import com.moviebooking.security.AccessPolicy;
import com.moviebooking.security.AuthenticatedUser;
import com.moviebooking.theater.Seat;
import com.moviebooking.theater.SeatCategory;
import com.moviebooking.theater.ShowtimeDetails;
import com.moviebooking.theater.TheaterService;

/** Read side of confirmed bookings. */
@Service
@Transactional(readOnly = true)
public class BookingService {

    private final BookingRepository bookings;
    private final ShowSeatRepository showSeats;
    private final TheaterService theaterService;
    private final CatalogService catalogService;

    public BookingService(BookingRepository bookings, ShowSeatRepository showSeats, TheaterService theaterService,
            CatalogService catalogService) {
        this.bookings = bookings;
        this.showSeats = showSeats;
        this.theaterService = theaterService;
        this.catalogService = catalogService;
    }

    /** Bookings of other customers are reported as not found. */
    public BookingConfirmation getForCustomer(AuthenticatedUser customer, UUID bookingId) {
        Booking booking = bookings.findById(bookingId).orElseThrow(ResourceNotFoundException::new);
        AccessPolicy.requireOwner(customer, booking.getCustomerId());
        return toConfirmation(booking);
    }

    /** The booking created by a SUCCEEDED attempt; it exists because both commit together. */
    public BookingConfirmation getForPayment(UUID paymentAttemptId) {
        Booking booking = bookings.findByPaymentAttemptId(paymentAttemptId)
                .orElseThrow(() -> new IllegalStateException("No booking for payment " + paymentAttemptId));
        return toConfirmation(booking);
    }

    private BookingConfirmation toConfirmation(Booking booking) {
        ShowtimeDetails showtime = theaterService.getShowtimeDetails(booking.getShowtimeId());
        Map<Long, Seat> seatsById = theaterService.getSeats(showtime.screenId()).stream()
                .collect(Collectors.toMap(Seat::getId, Function.identity()));
        List<BookedSeat> seats = showSeats.findByBookingIdOrderBySeatId(booking.getId()).stream()
                .map(showSeat -> {
                    Seat seat = seatsById.get(showSeat.getSeatId());
                    return new BookedSeat(seat.getId(), seat.getRowLabel(), seat.getSeatNumber(), seat.getCategory(),
                            showSeat.getPrice());
                })
                .toList();
        return new BookingConfirmation(booking.getId(), booking.getReference(), booking.getStatus(),
                booking.getPaymentAttemptId(), showtime.showtimeId(),
                catalogService.getMovie(showtime.movieId()).getTitle(), showtime.theaterName(),
                showtime.screenName(), showtime.city(), showtime.startsAt(), seats, booking.getTotalAmount(),
                booking.getCreatedAt());
    }

    public record BookingConfirmation(UUID bookingId, String reference, String status, UUID paymentId,
            long showtimeId, String movieTitle, String theaterName, String screenName, String city,
            Instant startsAt, List<BookedSeat> seats, BigDecimal totalAmount, Instant confirmedAt) {
    }

    public record BookedSeat(long seatId, String rowLabel, int seatNumber, SeatCategory category,
            BigDecimal price) {
    }
}
