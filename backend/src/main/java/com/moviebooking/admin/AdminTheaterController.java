package com.moviebooking.admin;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.moviebooking.security.AuthenticatedUser;
import com.moviebooking.theater.NewScreen;
import com.moviebooking.theater.ScreenDetail;
import com.moviebooking.theater.SeatLayout;
import com.moviebooking.theater.TheaterOverview;
import com.moviebooking.theater.TheaterSchedule;

/** The THEATER_ADMIN role is required by SecurityConfig; theater scoping is checked in the service. */
@RestController
@RequestMapping("/api/admin")
public class AdminTheaterController {

    private final AdminTheaterService adminTheaterService;

    public AdminTheaterController(AdminTheaterService adminTheaterService) {
        this.adminTheaterService = adminTheaterService;
    }

    @GetMapping("/theaters")
    public List<TheaterOverview> theaters(@AuthenticationPrincipal AuthenticatedUser admin) {
        return adminTheaterService.listTheaters(admin);
    }

    @GetMapping("/theaters/{theaterId}/showtimes")
    public TheaterSchedule schedule(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable long theaterId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return adminTheaterService.getSchedule(admin, theaterId, date);
    }

    @PostMapping("/theaters/{theaterId}/screens")
    @ResponseStatus(HttpStatus.CREATED)
    public ScreenDetail createScreen(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable long theaterId,
            @RequestBody NewScreen request) {
        return adminTheaterService.createScreen(admin, theaterId, request);
    }

    @GetMapping("/screens/{screenId}")
    public ScreenDetail screen(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable long screenId) {
        return adminTheaterService.getScreen(admin, screenId);
    }

    @PutMapping("/screens/{screenId}/layout")
    public ScreenDetail replaceLayout(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable long screenId,
            @RequestBody SeatLayout layout) {
        return adminTheaterService.replaceLayout(admin, screenId, layout);
    }
}
