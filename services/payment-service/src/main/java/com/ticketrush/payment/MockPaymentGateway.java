package com.ticketrush.payment;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.web.ApiException;

/**
 * Plays the payment provider for the demo: it "charges" the card, then signs the webhook with the
 * shared secret and delivers it the way the provider would. Delivery is in-process so the demo needs
 * no public URL, but it goes through the same signature check as a webhook arriving over HTTP.
 */
@Component
class MockPaymentGateway {

    record CheckoutResult(String transactionId, PaymentView payment) {
    }

    private final PaymentService payments;
    private final WebhookSignature signature;
    private final WebhookReceiver receiver;
    private final JsonMapper json;

    MockPaymentGateway(PaymentService payments, WebhookSignature signature, WebhookReceiver receiver, JsonMapper json) {
        this.payments = payments;
        this.signature = signature;
        this.receiver = receiver;
        this.json = json;
    }

    CheckoutResult checkout(UUID paymentId, GatewayWebhook.Outcome outcome) {
        PaymentView payment = payments.getForCheckout(paymentId);
        if (payment.status() != PaymentStatus.PENDING) {
            throw ApiException.conflict("Payment %s is %s".formatted(paymentId, payment.status()))
                    .with("status", payment.status());
        }
        String transactionId = "mock_" + UUID.randomUUID();
        byte[] body = json.writeValueAsBytes(new GatewayWebhook(paymentId, transactionId, outcome));
        return new CheckoutResult(transactionId, receiver.receive(signature.sign(body, Instant.now()), body));
    }
}
