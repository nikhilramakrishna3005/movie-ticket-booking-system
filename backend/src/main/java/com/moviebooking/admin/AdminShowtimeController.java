package com.moviebooking.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.moviebooking.security.AuthenticatedUser;
import com.moviebooking.theater.ShowtimeScheduler;
import com.moviebooking.theater.ShowtimeScheduler.ScheduledShowtime;

@RestController
@RequestMapping("/api/admin/showtimes")
public class AdminShowtimeController {

    private final ShowtimeScheduler scheduler;

    public AdminShowtimeController(ShowtimeScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * {@code startsAt} is a wall-clock time in America/Chicago, with no offset. The showing ends
     * at the movie duration plus 15 minutes, and every seat on the screen is seeded AVAILABLE.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduledShowtime create(@AuthenticationPrincipal AuthenticatedUser admin,
            @RequestBody CreateShowtimeRequest request) {
        return scheduler.create(admin, request.screenId(), request.movieId(), request.languageCode(),
                request.startsAt(), request.standardPrice(), request.premiumPrice());
    }

    public record CreateShowtimeRequest(Long screenId, Long movieId, String languageCode, LocalDateTime startsAt,
            BigDecimal standardPrice, BigDecimal premiumPrice) {
    }
}