package com.ticketrush.payment.web;

import java.util.UUID;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketrush.contracts.payment.PaymentStatusView;
import com.ticketrush.payment.domain.PaymentService;

/**
 * Service-to-service lookup the booking saga uses to check a {@code PaymentSucceeded} event against the
 * authoritative payment record before it confirms a booking (FR-PAY-06, NFR-SEC-01). The gateway routes
 * only {@code /api/**}, so this {@code /internal} path is reachable only from inside the cluster.
 */
@RestController
@RequestMapping("/internal/payments")
class InternalPaymentController {

    private final PaymentService payments;

    InternalPaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @SecurityRequirements // internal only; not exposed through the gateway
    @GetMapping("/{bookingId}")
    PaymentStatusView byBooking(@PathVariable UUID bookingId) {
        return payments.statusByBooking(bookingId);
    }
}
