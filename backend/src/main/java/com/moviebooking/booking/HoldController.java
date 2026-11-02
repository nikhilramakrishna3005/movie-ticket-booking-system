package com.moviebooking.booking;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.moviebooking.booking.HoldService.HoldResult;
import com.moviebooking.booking.SeatMapService.SeatMap;
import com.moviebooking.security.AuthenticatedUser;

@RestController
@RequestMapping("/api")
public class HoldController {

    private final HoldService holdService;
    private final SeatMapService seatMapService;

    public HoldController(HoldService holdService, SeatMapService seatMapService) {
        this.holdService = holdService;
        this.seatMapService = seatMapService;
    }

    @GetMapping("/showtimes/{showtimeId}/seats")
    public SeatMap seatMap(@PathVariable long showtimeId) {
        return seatMapService.getSeatMap(showtimeId);
    }

    @PostMapping("/showtimes/{showtimeId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResult createHold(@AuthenticationPrincipal AuthenticatedUser customer,
            @PathVariable long showtimeId, @RequestBody CreateHoldRequest request) {
        return holdService.createHold(customer, showtimeId, request.seatIds());
    }

    @DeleteMapping("/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releaseHold(@AuthenticationPrincipal AuthenticatedUser customer, @PathVariable UUID holdId) {
        holdService.releaseHold(customer, holdId);
    }

    /** Size and duplicate rules are enforced by {@link HoldService} so they hold for every caller. */
    public record CreateHoldRequest(List<Long> seatIds) {
    }
}
