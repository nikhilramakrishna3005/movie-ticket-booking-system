package com.moviebooking.admin;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.moviebooking.booking.ShowtimeCancellationService;
import com.moviebooking.config.ApiException;
import com.moviebooking.config.ResourceNotFoundException;
import com.moviebooking.security.AccessPolicy;
import com.moviebooking.security.AuthenticatedUser;
import com.moviebooking.theater.CancelledShowtime;
import com.moviebooking.theater.CreatedShowtime;
import com.moviebooking.theater.NewShowtime;
import com.moviebooking.theater.Screen;
import com.moviebooking.theater.Showtime;
import com.moviebooking.theater.TheaterService;

/**
 * Theater-scoped admin actions. Owns no tables; theater creates showtimes and booking cancels
 * them, because only booking may inspect holds, payments and seat ownership.
 */
@Service
public class AdminShowtimeService {

    private final TheaterService theaterService;
    private final ShowtimeCancellationService cancellation;

    public AdminShowtimeService(TheaterService theaterService, ShowtimeCancellationService cancellation) {
        this.theaterService = theaterService;
        this.cancellation = cancellation;
    }

    /**
     * The theater is derived from the screen, so an admin can only schedule on screens of a
     * theater assigned to them (403 otherwise). A screen's theater never changes, so checking
     * before the creating transaction is safe.
     */
    public CreatedShowtime createShowtime(AuthenticatedUser admin, NewShowtime request) {
        List<String> invalid = request.invalidFields();
        if (!invalid.isEmpty()) {
            throw ApiException.badRequest("INVALID_REQUEST", "Missing or invalid fields", Map.of("fields", invalid));
        }
        Screen screen = theaterService.findScreen(request.screenId())
                .orElseThrow(() -> ApiException.badRequest("INVALID_REQUEST", "Unknown screenId",
                        Map.of("fields", List.of("screenId"))));
        AccessPolicy.requireAssignedTheater(admin, screen.getTheaterId());
        return theaterService.createShowtime(request);
    }

    /**
     * Unknown showtime: 404. Known showtime of a theater this admin is not assigned to: 403.
     * Assignment is checked before the cancelling transaction so a foreign theater is never locked.
     */
    public CancelledShowtime cancelShowtime(AuthenticatedUser admin, long showtimeId) {
        Showtime showtime = theaterService.getShowtime(showtimeId);
        long theaterId = theaterService.findScreen(showtime.getScreenId())
                .orElseThrow(ResourceNotFoundException::new)
                .getTheaterId();
        AccessPolicy.requireAssignedTheater(admin, theaterId);
        return cancellation.cancel(showtimeId);
    }
}
