package com.ticketrush.payment;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ticketrush.common.web.ApiException;
import com.ticketrush.common.web.RequestHeaders;

@RestController
@RequestMapping("/api/payments")
class PaymentController {

    record CheckoutRequest(@NotNull GatewayWebhook.Outcome outcome) {
    }

    record CheckoutResult(String transactionId, PaymentView payment) {
    }

    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping("/{id}")
    PaymentView get(@RequestHeader(RequestHeaders.USER_ID) String userId, @PathVariable UUID id) {
        return payments.get(id, userId);
    }

    @GetMapping
    PaymentView byBooking(@RequestHeader(RequestHeaders.USER_ID) String userId, @RequestParam UUID bookingId) {
        return payments.getByBooking(bookingId, userId);
    }

    /**
     * Mock gateway checkout: the customer "pays" or is declined, and the gateway calls the webhook below.
     * In production this page belongs to the payment provider; the payment id in the link is the secret.
     */
    @PostMapping("/{id}/checkout")
    CheckoutResult checkout(@PathVariable UUID id, @Valid @RequestBody CheckoutRequest request) {
        PaymentView payment = payments.getForCheckout(id);
        if (payment.status() != PaymentStatus.PENDING) {
            throw ApiException.conflict("Payment %s is %s".formatted(id, payment.status())).with("status", payment.status());
        }
        String transactionId = "mock_" + UUID.randomUUID();
        return new CheckoutResult(transactionId, payments.handleWebhook(new GatewayWebhook(id, transactionId, request.outcome())));
    }

    /** Webhook receiver. Always answers 200 with the current state, so gateway retries stop (FR-PAY-03). */
    @PostMapping("/webhooks/mock-gateway")
    PaymentView webhook(@Valid @RequestBody GatewayWebhook webhook) {
        return payments.handleWebhook(webhook);
    }
}
