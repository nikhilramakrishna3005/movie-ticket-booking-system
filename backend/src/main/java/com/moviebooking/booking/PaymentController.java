package com.moviebooking.booking;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moviebooking.booking.BookingService.BookingConfirmation;
import com.moviebooking.booking.CheckoutService.PaymentOutcome;
import com.moviebooking.config.ApiException;
import com.moviebooking.payment.PaymentScenario;
import com.moviebooking.payment.PaymentStatus;
import com.moviebooking.payment.PaymentView;
import com.moviebooking.security.AuthenticatedUser;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final CheckoutService checkoutService;
    private final BookingService bookingService;

    public PaymentController(CheckoutService checkoutService, BookingService bookingService) {
        this.checkoutService = checkoutService;
        this.bookingService = bookingService;
    }

    /**
     * 201 with the booking, or 202 while the outcome is UNKNOWN (poll the payment). A replayed
     * idempotency key gets the same mapping for the attempt's current state.
     */
    @PostMapping("/holds/{holdId}/payment")
    public ResponseEntity<PaymentResponse> pay(@AuthenticationPrincipal AuthenticatedUser customer,
            @PathVariable UUID holdId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PayRequest request) {
        PaymentOutcome outcome = checkoutService.pay(customer, holdId, idempotencyKey, request.scenario());
        PaymentView payment = outcome.payment();
        Map<String, Object> details = Map.of("paymentId", payment.id(), "status", payment.status());
        return switch (payment.status()) {
            case SUCCEEDED -> ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.of(outcome));
            case UNKNOWN -> ResponseEntity.status(HttpStatus.ACCEPTED).body(PaymentResponse.of(outcome));
            case PENDING -> throw ApiException.conflict("PAYMENT_IN_PROGRESS",
                    "A payment for this hold is already in progress", details);
            case DECLINED -> throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED",
                    "The payment was declined; retry with a new Idempotency-Key", details);
            case VOIDED -> throw ApiException.conflict("PAYMENT_VOIDED",
                    "The hold could no longer be booked, so the charge was voided", details);
        };
    }

    @GetMapping("/payments/{paymentId}")
    public PaymentResponse payment(@AuthenticationPrincipal AuthenticatedUser customer,
            @PathVariable UUID paymentId) {
        return PaymentResponse.of(checkoutService.getPayment(customer, paymentId));
    }

    @GetMapping("/bookings/{bookingId}")
    public BookingConfirmation booking(@AuthenticationPrincipal AuthenticatedUser customer,
            @PathVariable UUID bookingId) {
        return bookingService.getForCustomer(customer, bookingId);
    }

    public record PayRequest(PaymentScenario scenario) {
    }

    /** Leaves out provider-facing details (idempotency key, scenario, decline reason). */
    public record PaymentResponse(UUID paymentId, UUID holdId, PaymentStatus status, BigDecimal amount,
            BookingConfirmation booking) {

        static PaymentResponse of(PaymentOutcome outcome) {
            PaymentView payment = outcome.payment();
            return new PaymentResponse(payment.id(), payment.holdId(), payment.status(), payment.amount(),
                    outcome.booking());
        }
    }
}
