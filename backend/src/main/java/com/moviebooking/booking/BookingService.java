package com.moviebooking.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

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

    /**
     * The caller's own confirmed bookings, newest first, with id as a stable tie-breaker. The
     * customer is always the authenticated principal. At most seven queries per page however large
     * it is: the page, its count, and batched lookups of booked seats, showtimes, venues, movie
     * titles and seat labels.
     */
    public BookingPage listForCustomer(AuthenticatedUser customer, BookingPageRequest request) {
        Page<Booking> page = bookings.findByCustomerIdAndStatus(customer.id(), Booking.CONFIRMED,
                PageRequest.of(request.page(), request.size(), NEWEST_FIRST));
        List<Booking> content = page.getContent();
        if (content.isEmpty()) {
            return new BookingPage(List.of(), request.page(), request.size(), page.getTotalElements(),
                    page.getTotalPages());
        }

        Map<UUID, List<ShowSeat>> seatsByBooking = showSeats
                .findByBookingIdInOrderBySeatId(content.stream().map(Booking::getId).toList()).stream()
                .collect(Collectors.groupingBy(ShowSeat::getBookingId));
        Map<Long, ShowtimeDetails> showtimes = theaterService
                .getShowtimeDetails(content.stream().map(Booking::getShowtimeId).collect(Collectors.toSet()));
        Map<Long, String> titles = catalogService
                .getMovieTitles(showtimes.values().stream().map(ShowtimeDetails::movieId).collect(Collectors.toSet()));
        Map<Long, Seat> seatsById = theaterService.getSeatsById(seatsByBooking.values().stream()
                .flatMap(List::stream).map(ShowSeat::getSeatId).collect(Collectors.toSet()));

        List<BookingSummary> items = content.stream().map(booking -> {
            ShowtimeDetails showtime = showtimes.get(booking.getShowtimeId());
            ZonedDateTime localStart = showtime.startsAt().atZone(showtime.zone());
            List<BookedSeat> seats = seatsByBooking.getOrDefault(booking.getId(), List.of()).stream()
                    .map(showSeat -> bookedSeat(seatsById.get(showSeat.getSeatId()), showSeat))
                    .toList();
            return new BookingSummary(booking.getId(), booking.getReference(), booking.getStatus(),
                    showtime.showtimeId(), titles.get(showtime.movieId()), showtime.theaterName(),
                    showtime.screenName(), showtime.city(), showtime.startsAt(), localStart.toLocalDate(),
                    localStart.toLocalTime(), showtime.zone().getId(), seats, booking.getTotalAmount(),
                    booking.getCreatedAt());
        }).toList();
        return new BookingPage(items, request.page(), request.size(), page.getTotalElements(), page.getTotalPages());
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
                .map(showSeat -> bookedSeat(seatsById.get(showSeat.getSeatId()), showSeat))
                .toList();
        return new BookingConfirmation(booking.getId(), booking.getReference(), booking.getStatus(),
                booking.getPaymentAttemptId(), showtime.showtimeId(),
                catalogService.getMovie(showtime.movieId()).getTitle(), showtime.theaterName(),
                showtime.screenName(), showtime.city(), showtime.startsAt(), seats, booking.getTotalAmount(),
                booking.getCreatedAt());
    }

    private static BookedSeat bookedSeat(Seat seat, ShowSeat showSeat) {
        return new BookedSeat(seat.getId(), seat.getRowLabel(), seat.getSeatNumber(), seat.getCategory(),
                showSeat.getPrice());
    }

    public record BookingConfirmation(UUID bookingId, String reference, String status, UUID paymentId,
            long showtimeId, String movieTitle, String theaterName, String screenName, String city,
            Instant startsAt, List<BookedSeat> seats, BigDecimal totalAmount, Instant confirmedAt) {
    }

    public record BookedSeat(long seatId, String rowLabel, int seatNumber, SeatCategory category,
            BigDecimal price) {
    }

    /** One entry of the booking history; {@code localDate} and {@code localStartTime} are in {@code timeZone}. */
    public record BookingSummary(UUID bookingId, String reference, String status, long showtimeId,
            String movieTitle, String theaterName, String screenName, String city, Instant startsAt,
            LocalDate localDate, LocalTime localStartTime, String timeZone, List<BookedSeat> seats,
            BigDecimal totalAmount, Instant confirmedAt) {
    }

    public record BookingPage(List<BookingSummary> items, int page, int size, long totalItems, int totalPages) {
    }
}
