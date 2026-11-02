package com.moviebooking.admin;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.moviebooking.config.ApiException;
import com.moviebooking.security.AccessPolicy;
import com.moviebooking.security.AuthenticatedUser;
import com.moviebooking.theater.CreatedShowtime;
import com.moviebooking.theater.NewShowtime;
import com.moviebooking.theater.Screen;
import com.moviebooking.theater.TheaterService;

/** Theater-scoped admin actions. Owns no tables; the theater package does the work. */
@Service
public class AdminShowtimeService {

    private final TheaterService theaterService;

    public AdminShowtimeService(TheaterService theaterService) {
        this.theaterService = theaterService;
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
}
