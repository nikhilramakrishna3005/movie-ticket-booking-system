package com.moviebooking.admin;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.moviebooking.security.AuthenticatedUser;
import com.moviebooking.theater.CreatedShowtime;
import com.moviebooking.theater.NewShowtime;

/** The THEATER_ADMIN role is required by SecurityConfig; theater scoping is checked in the service. */
@RestController
@RequestMapping("/api/admin")
public class AdminShowtimeController {

    private final AdminShowtimeService adminShowtimeService;

    public AdminShowtimeController(AdminShowtimeService adminShowtimeService) {
        this.adminShowtimeService = adminShowtimeService;
    }

    @PostMapping("/showtimes")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedShowtime createShowtime(@AuthenticationPrincipal AuthenticatedUser admin,
            @RequestBody NewShowtime request) {
        return adminShowtimeService.createShowtime(admin, request);
    }
}
