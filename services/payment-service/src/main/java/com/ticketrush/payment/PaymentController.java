package com.ticketrush.payment;

import java.io.IOException;
import java.util.UUID;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
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

import com.ticketrush.security.Caller;
import com.ticketrush.security.CustomerOnly;
import com.ticketrush.web.ApiException;

@RestController
@RequestMapping("/api/payments")
class PaymentController {

    /** A real gateway webhook is a few hundred bytes; the cap only has to leave room for that. */
    private static final int MAX_WEBHOOK_BODY_BYTES = 64 * 1024;

    record CheckoutRequest(@NotNull GatewayWebhook.Outcome outcome) {
    }

    private final PaymentService payments;
    private final MockPaymentGateway gateway;
    private final WebhookReceiver webhooks;

    PaymentController(PaymentService payments, MockPaymentGateway gateway, WebhookReceiver webhooks) {
        this.payments = payments;
        this.gateway = gateway;
        this.webhooks = webhooks;
    }

    @GetMapping("/{id}")
    @CustomerOnly
    PaymentView get(Caller caller, @PathVariable UUID id) {
        return payments.get(id, caller.id());
    }

    @GetMapping
    @CustomerOnly
    PaymentView byBooking(Caller caller, @RequestParam UUID bookingId) {
        return payments.getByBooking(bookingId, caller.id());
    }

    /**
     * Mock provider checkout page: the customer "pays" or is declined, and the provider sends the signed
     * webhook. In production this page belongs to the payment provider; the payment id in the link is the secret.
     */
    @SecurityRequirements // public: no token needed
    @PostMapping("/{id}/checkout")
    MockPaymentGateway.CheckoutResult checkout(@PathVariable UUID id, @Valid @RequestBody CheckoutRequest request) {
        return gateway.checkout(id, request.outcome());
    }

    /**
     * Webhook receiver. Unsigned, forged or stale webhooks get 401 (FR-PAY-04). Otherwise it always answers
     * 200 with the current state, so provider retries stop (FR-PAY-03).
     *
     * <p>The body is read with a hard cap instead of {@code @RequestBody byte[]}, which would buffer the
     * whole request before the signature is even checked: this endpoint is public, so an unbounded body is
     * a way to exhaust memory. A real webhook is a few hundred bytes; anything over the cap is 413.
     */
    @SecurityRequirements // public: no token needed
    @PostMapping("/webhooks/mock-gateway")
    PaymentView webhook(@RequestHeader(value = WebhookSignature.HEADER, required = false) String signature,
                        HttpServletRequest request) throws IOException {
        return webhooks.receive(signature, boundedBody(request));
    }

    private static byte[] boundedBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_WEBHOOK_BODY_BYTES) {
            throw ApiException.payloadTooLarge("Webhook body is larger than " + MAX_WEBHOOK_BODY_BYTES + " bytes");
        }
        // Read one byte past the cap so a chunked or mis-declared body over the cap is still caught, without
        // buffering more than the cap plus one byte in memory.
        byte[] body = request.getInputStream().readNBytes(MAX_WEBHOOK_BODY_BYTES + 1);
        if (body.length > MAX_WEBHOOK_BODY_BYTES) {
            throw ApiException.payloadTooLarge("Webhook body is larger than " + MAX_WEBHOOK_BODY_BYTES + " bytes");
        }
        return body;
    }
}
