package com.moviebooking.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.moviebooking.config.ApiException;
import com.moviebooking.config.ResourceNotFoundException;
import com.moviebooking.security.AccessPolicy;
import com.moviebooking.security.AuthenticatedUser;
import com.moviebooking.theater.NewScreen;
import com.moviebooking.theater.ScreenDetail;
import com.moviebooking.theater.SeatLayout;
import com.moviebooking.theater.TheaterOverview;
import com.moviebooking.theater.TheaterSchedule;
import com.moviebooking.theater.TheaterService;
import com.moviebooking.theater.VenueService;

/**
 * Theater-scoped reads and screen management. A theater or screen in the path is checked against
 * the admin's assignments before the request body is looked at, so another theater's resources
 * answer 403 whatever is sent. An unknown screen is 404; an unassigned theater id is 403 whether or
 * not it exists.
 */
@Service
public class AdminTheaterService {

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    private final TheaterService theaterService;
    private final VenueService venueService;

    public AdminTheaterService(TheaterService theaterService, VenueService venueService) {
        this.theaterService = theaterService;
        this.venueService = venueService;
    }

    /** Only the theaters the admin is assigned to; the list comes from the principal, never a parameter. */
    public List<TheaterOverview> listTheaters(AuthenticatedUser admin) {
        return venueService.getOverviews(admin.theaterIds());
    }

    /** {@code date} is local to the theater; null means today there. */
    public TheaterSchedule getSchedule(AuthenticatedUser admin, long theaterId, LocalDate date) {
        AccessPolicy.requireAssignedTheater(admin, theaterId);
        if (date != null && (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR)) {
            throw ApiException.badRequest("INVALID_REQUEST", "date must be between " + MIN_YEAR + " and " + MAX_YEAR,
                    Map.of("parameter", "date"));
        }
        return theaterService.getSchedule(theaterId, date == null ? theaterService.today(theaterId) : date);
    }

    public ScreenDetail getScreen(AuthenticatedUser admin, long screenId) {
        requireAssignedScreen(admin, screenId);
        return venueService.getScreenDetail(screenId);
    }

    public ScreenDetail createScreen(AuthenticatedUser admin, long theaterId, NewScreen request) {
        AccessPolicy.requireAssignedTheater(admin, theaterId);
        requireValid(request.invalidFields());
        return venueService.createScreen(theaterId, request);
    }

    public ScreenDetail replaceLayout(AuthenticatedUser admin, long screenId, SeatLayout layout) {
        requireAssignedScreen(admin, screenId);
        requireValid(layout.invalidFields());
        return venueService.replaceLayout(screenId, layout);
    }

    /** A screen never moves to another theater, so checking before the writing transaction is safe. */
    private void requireAssignedScreen(AuthenticatedUser admin, long screenId) {
        long theaterId = theaterService.findScreen(screenId).orElseThrow(ResourceNotFoundException::new).getTheaterId();
        AccessPolicy.requireAssignedTheater(admin, theaterId);
    }

    private static void requireValid(List<String> invalidFields) {
        if (!invalidFields.isEmpty()) {
            throw ApiException.badRequest("INVALID_REQUEST", "Missing or invalid fields", Map.of("fields", invalidFields));
        }
    }
}
