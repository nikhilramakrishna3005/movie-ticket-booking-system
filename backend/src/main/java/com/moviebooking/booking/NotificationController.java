package com.moviebooking.booking;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moviebooking.booking.NotificationService.NotificationPage;
import com.moviebooking.security.AuthenticatedUser;

@RestController
@RequestMapping("/api")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** The caller's inbox only; there is no customer parameter. */
    @GetMapping("/notifications")
    public NotificationPage list(@AuthenticationPrincipal AuthenticatedUser customer,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return notifications.listForCustomer(customer, BookingPageRequest.of(page, size));
    }
}
