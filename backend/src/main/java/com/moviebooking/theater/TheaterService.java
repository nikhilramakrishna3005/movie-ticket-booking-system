package com.moviebooking.theater;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.catalog.CatalogService;
import com.moviebooking.catalog.Movie;
import com.moviebooking.config.ApiException;
import com.moviebooking.config.ResourceNotFoundException;

/** Cities, screens, showtimes and physical seats; schedules new showtimes with their seat inventory. */
@Service
@Transactional(readOnly = true)
public class TheaterService {

    /** Gap after each showtime before the next may start on the same screen (same rule as the V7 seed). */
    public static final Duration CLEANING_TIME = Duration.ofMinutes(15);

    /** SQLSTATE exclusion_violation; {@code showtime_no_overlap_ex} is the only exclusion constraint on showtime. */
    static final String EXCLUSION_VIOLATION = "23P01";

    private final ShowtimeRepository showtimes;
    private final SeatRepository seats;
    private final ScreenRepository screens;
    private final CityRepository cities;
    private final CatalogService catalogService;
    private final Clock clock;
    private final String lockTimeout;

    /** Uses the same lock-wait bound as the booking transactions. */
    public TheaterService(ShowtimeRepository showtimes, SeatRepository seats, ScreenRepository screens,
            CityRepository cities, CatalogService catalogService, Clock clock,
            @Value("${booking.lock-timeout}") Duration lockTimeout) {
        this.showtimes = showtimes;
        this.seats = seats;
        this.screens = screens;
        this.cities = cities;
        this.catalogService = catalogService;
        this.clock = clock;
        this.lockTimeout = lockTimeout.toMillis() + "ms";
    }

    public List<CityView> listCities() {
        return cities.findAllByOrderByNameAscStateCodeAsc().stream()
                .map(city -> new CityView(city.getId(), city.getName(), city.getStateCode(), city.getZone().getId()))
                .toList();
    }

    public Optional<Screen> findScreen(long screenId) {
        return screens.findById(screenId);
    }

    public Showtime getShowtime(long showtimeId) {
        return showtimes.findById(showtimeId).orElseThrow(ResourceNotFoundException::new);
    }

    public ShowtimeDetails getShowtimeDetails(long showtimeId) {
        ShowtimeDetails details = getShowtimeDetails(List.of(showtimeId)).get(showtimeId);
        if (details == null) {
            throw new ResourceNotFoundException();
        }
        return details;
    }

    /** Details keyed by showtime id, in two queries however many ids are given; unknown ids are absent. */
    public Map<Long, ShowtimeDetails> getShowtimeDetails(Collection<Long> showtimeIds) {
        if (showtimeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ShowtimeRepository.VenueNames> venues = showtimes.findVenueNames(showtimeIds).stream()
                .collect(Collectors.toMap(ShowtimeRepository.VenueNames::getShowtimeId, Function.identity()));
        return showtimes.findAllById(showtimeIds).stream()
                .filter(showtime -> venues.containsKey(showtime.getId()))
                .collect(Collectors.toMap(Showtime::getId, showtime -> {
                    ShowtimeRepository.VenueNames venue = venues.get(showtime.getId());
                    return new ShowtimeDetails(showtime.getId(), showtime.getMovieId(), showtime.getScreenId(),
                            showtime.getStartsAt(), venue.getTheaterName(), venue.getScreenName(),
                            venue.getCityName() + ", " + venue.getStateCode(), ZoneId.of(venue.getTimeZone()));
                }));
    }

    /** Seats of a screen in display order (row, then seat number). */
    public List<Seat> getSeats(long screenId) {
        return seats.findByScreenIdOrderByRowLabelAscSeatNumberAsc(screenId);
    }

    /** Seats keyed by id; unknown ids are absent. */
    public Map<Long, Seat> getSeatsById(Collection<Long> seatIds) {
        return seats.findAllById(seatIds).stream().collect(Collectors.toMap(Seat::getId, Function.identity()));
    }

    /** Today in the theater's zone at the clock's current instant. */
    public LocalDate today(long theaterId) {
        return LocalDate.ofInstant(clock.instant(), cities.findByTheaterId(theaterId).orElseThrow().getZone());
    }

    /**
     * The local day is a half-open UTC range computed with the city's zone rules, so it is 23 or 25
     * hours long on daylight-saving days.
     */
    public TheaterSchedule getSchedule(long theaterId, LocalDate date) {
        ZoneId zone = cities.findByTheaterId(theaterId).orElseThrow().getZone();
        OffsetDateTime from = date.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        List<ShowtimeRepository.ScheduleRow> rows = showtimes.findSchedule(theaterId, from, to, now);
        Map<Long, String> titles = catalogService.getMovieTitles(
                rows.stream().map(ShowtimeRepository.ScheduleRow::getMovieId).distinct().toList());
        List<TheaterSchedule.Entry> entries = rows.stream()
                .map(row -> new TheaterSchedule.Entry(row.getShowtimeId(), row.getScreenId(), row.getScreenName(),
                        row.getMovieId(), titles.get(row.getMovieId()), row.getLanguageCode(),
                        ShowtimeStatus.valueOf(row.getStatus()), row.getStartsAt(), row.getEndsAt(),
                        row.getStartsAt().atZone(zone).toLocalTime(), row.getEndsAt().atZone(zone).toLocalTime(),
                        row.getSeatCount().intValue(), row.getBookedSeats().intValue(), row.getHeldSeats().intValue(),
                        row.getStandardPrice(), row.getPremiumPrice()))
                .toList();
        return new TheaterSchedule(theaterId, date, zone.getId(), entries);
    }

    /**
     * Inserts a SCHEDULED showtime and one AVAILABLE {@code show_seat} row per seat of the screen,
     * in one transaction. Overlap with another scheduled showtime on the screen is decided only by
     * the {@code showtime_no_overlap_ex} exclusion constraint; the screen row lock makes concurrent
     * creations on one screen take turns, so the loser sees a plain violation, not a deadlock.
     * Callers must already have checked that the admin may manage the screen's theater.
     */
    @Transactional
    public CreatedShowtime createShowtime(NewShowtime request) {
        screens.setLocalLockTimeout(lockTimeout);
        Screen screen;
        try {
            screen = screens.lockForScheduling(request.screenId()).orElseThrow(() -> invalidField("screenId"));
        } catch (PessimisticLockingFailureException ex) {
            throw ApiException.conflict("SCREEN_BUSY", "Another showtime is being scheduled on this screen; retry",
                    Map.of("screenId", request.screenId()));
        }
        Movie movie = catalogService.findMovie(request.movieId()).orElseThrow(() -> invalidField("movieId"));
        if (!catalogService.languageExists(request.languageCode())) {
            throw invalidField("languageCode");
        }
        ZoneId zone = cities.findByTheaterId(screen.getTheaterId()).orElseThrow().getZone();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant startsAt = resolveLocalStart(request.startsAtLocal(), zone);
        if (!startsAt.isAfter(now)) {
            throw ApiException.badRequest("INVALID_START_TIME", "The start time must be in the future",
                    Map.of("startsAtLocal", request.startsAtLocal(), "timeZone", zone.getId()));
        }
        Instant endsAt = startsAt.plus(Duration.ofMinutes(movie.getDurationMinutes())).plus(CLEANING_TIME);

        Showtime showtime = new Showtime(screen.getId(), movie.getId(), request.languageCode(), startsAt, endsAt, now);
        try {
            showtimes.saveAndFlush(showtime);
        } catch (DataIntegrityViolationException ex) {
            if (ex.getCause() instanceof ConstraintViolationException violation
                    && EXCLUSION_VIOLATION.equals(violation.getSQLState())) {
                throw ApiException.conflict("SHOWTIME_OVERLAP",
                        "The screen already has a scheduled showtime in this time range",
                        Map.of("screenId", screen.getId(), "startsAt", startsAt, "endsAt", endsAt));
            }
            throw ex;
        }
        int seatCount = showtimes.createSeatInventory(showtime.getId(), request.standardPrice(),
                request.premiumPrice());

        ZonedDateTime localStart = startsAt.atZone(zone);
        return new CreatedShowtime(showtime.getId(), screen.getTheaterId(), screen.getId(), movie.getId(),
                request.languageCode(), showtime.getStatus(), startsAt, endsAt, localStart.toLocalDate(),
                localStart.toLocalTime(), zone.getId(), seatCount);
    }

    /**
     * A local wall-clock time that is skipped (spring forward) or occurs twice (fall back) in the
     * zone is rejected rather than silently shifted or guessed.
     */
    static Instant resolveLocalStart(LocalDateTime local, ZoneId zone) {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) {
            throw ApiException.badRequest("INVALID_START_TIME",
                    "The local start time does not exist or is ambiguous because of a daylight-saving change",
                    Map.of("startsAtLocal", local, "timeZone", zone.getId()));
        }
        return local.toInstant(offsets.getFirst());
    }

    private static ApiException invalidField(String field) {
        return ApiException.badRequest("INVALID_REQUEST", "Unknown " + field, Map.of("fields", List.of(field)));
    }
}
