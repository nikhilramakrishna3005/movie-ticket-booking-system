package com.moviebooking.theater;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.config.ApiException;
import com.moviebooking.config.ResourceNotFoundException;

/**
 * Theaters, screens and seating plans. A plan can be replaced only while the screen has never had a
 * showtime: every showtime's inventory and bookings reference the physical seats, so changing a
 * scheduled screen's seats would rewrite tickets that were already sold.
 */
@Service
@Transactional(readOnly = true)
public class VenueService {

    static final String SCREEN_NAME_UNIQUE = "screen_theater_name_uk";

    private final TheaterRepository theaters;
    private final ScreenRepository screens;
    private final SeatRepository seats;
    private final ShowtimeRepository showtimes;
    private final CityRepository cities;
    private final String lockTimeout;

    public VenueService(TheaterRepository theaters, ScreenRepository screens, SeatRepository seats,
            ShowtimeRepository showtimes, CityRepository cities,
            @Value("${booking.lock-timeout}") Duration lockTimeout) {
        this.theaters = theaters;
        this.screens = screens;
        this.seats = seats;
        this.showtimes = showtimes;
        this.cities = cities;
        this.lockTimeout = lockTimeout.toMillis() + "ms";
    }

    /** The given theaters by name, each with its screens and seat counts, in a fixed number of queries. */
    public List<TheaterOverview> getOverviews(Collection<Long> theaterIds) {
        if (theaterIds.isEmpty()) {
            return List.of();
        }
        List<Theater> found = theaters.findByIdInOrderByNameAsc(theaterIds);
        Map<Long, City> citiesById = cities.findAllById(found.stream().map(Theater::getCityId).toList()).stream()
                .collect(Collectors.toMap(City::getId, Function.identity()));
        List<Screen> allScreens = screens.findByTheaterIdInOrderByNameAscIdAsc(theaterIds);
        List<Long> screenIds = allScreens.stream().map(Screen::getId).toList();
        Map<Long, List<Seat>> seatsByScreen = screenIds.isEmpty() ? Map.of()
                : seats.findByScreenIdIn(screenIds).stream().collect(Collectors.groupingBy(Seat::getScreenId));
        Set<Long> locked = screenIds.isEmpty() ? Set.of() : Set.copyOf(showtimes.findScreensWithShowtimes(screenIds));

        return found.stream().map(theater -> {
            City city = citiesById.get(theater.getCityId());
            List<TheaterOverview.ScreenSummary> summaries = allScreens.stream()
                    .filter(screen -> screen.getTheaterId() == theater.getId())
                    .map(screen -> summary(screen, seatsByScreen.getOrDefault(screen.getId(), List.of()),
                            locked.contains(screen.getId())))
                    .toList();
            return new TheaterOverview(theater.getId(), theater.getName(), theater.getAddress(),
                    new CityView(city.getId(), city.getName(), city.getStateCode(), city.getZone().getId()), summaries);
        }).toList();
    }

    public ScreenDetail getScreenDetail(long screenId) {
        Screen screen = screens.findById(screenId).orElseThrow(ResourceNotFoundException::new);
        return detail(screen, showtimes.existsByScreenId(screenId));
    }

    /** The theater must exist; callers check that the admin is assigned to it first. */
    @Transactional
    public ScreenDetail createScreen(long theaterId, NewScreen request) {
        Screen screen = new Screen(theaterId, request.trimmedName());
        try {
            screens.saveAndFlush(screen);
        } catch (DataIntegrityViolationException ex) {
            if (ex.getCause() instanceof ConstraintViolationException violation
                    && SCREEN_NAME_UNIQUE.equals(violation.getConstraintName())) {
                throw ApiException.conflict("SCREEN_NAME_TAKEN", "The theater already has a screen with this name",
                        Map.of("name", request.trimmedName()));
            }
            throw ex;
        }
        insertRows(screen.getId(), request.layout());
        return detail(screen, false);
    }

    /**
     * Holds the same screen lock as showtime creation, so no showtime can be scheduled between the
     * "never scheduled" check and the new seats being written.
     */
    @Transactional
    public ScreenDetail replaceLayout(long screenId, SeatLayout layout) {
        screens.setLocalLockTimeout(lockTimeout);
        Screen screen;
        try {
            screen = screens.lockForScheduling(screenId).orElseThrow(ResourceNotFoundException::new);
        } catch (PessimisticLockingFailureException ex) {
            throw ApiException.conflict("SCREEN_BUSY", "This screen is being changed by another request; retry",
                    Map.of("screenId", screenId));
        }
        if (showtimes.existsByScreenId(screenId)) {
            throw ApiException.conflict("SCREEN_HAS_SHOWTIMES",
                    "The seating plan is fixed once a showtime has been scheduled on this screen",
                    Map.of("screenId", screenId));
        }
        seats.deleteByScreen(screenId);
        insertRows(screenId, layout);
        return detail(screen, false);
    }

    private void insertRows(long screenId, SeatLayout layout) {
        for (SeatLayout.Row row : layout.rows()) {
            seats.insertRow(screenId, row.rowLabel(), row.seatCount(), row.category());
        }
    }

    private ScreenDetail detail(Screen screen, boolean layoutLocked) {
        List<ScreenDetail.SeatView> seatViews = seats.findByScreenIdOrderByRowLabelAscSeatNumberAsc(screen.getId())
                .stream()
                .map(seat -> new ScreenDetail.SeatView(seat.getId(), seat.getRowLabel(), seat.getSeatNumber(),
                        seat.getCategory()))
                .toList();
        return new ScreenDetail(screen.getId(), screen.getTheaterId(), screen.getName(), layoutLocked, seatViews);
    }

    private static TheaterOverview.ScreenSummary summary(Screen screen, List<Seat> screenSeats, boolean locked) {
        int premium = (int) screenSeats.stream().filter(seat -> seat.getCategory() == SeatCategory.PREMIUM).count();
        int rows = (int) screenSeats.stream().map(Seat::getRowLabel).distinct().count();
        return new TheaterOverview.ScreenSummary(screen.getId(), screen.getName(), rows, screenSeats.size(),
                screenSeats.size() - premium, premium, locked);
    }
}
