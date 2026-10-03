package com.ticketrush.booking;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.ticketrush.contracts.payment.PaymentStatusView;

/**
 * Asks payment-service, over {@code /internal/payments/{bookingId}}, whether a booking is really paid.
 * Short timeouts: if payment-service is slow or down the call fails and the event is retried, rather than
 * blocking the listener. A 404 means no such payment, i.e. the event does not match a real payment.
 */
@Component
class HttpPaymentVerifier implements PaymentVerifier {

    private static final Logger log = LoggerFactory.getLogger(HttpPaymentVerifier.class);

    private final RestClient client;

    HttpPaymentVerifier(@Value("${ticketrush.payment.base-url:http://localhost:8083}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(2000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public boolean confirmsPaid(UUID bookingId, UUID paymentId) {
        PaymentStatusView payment;
        try {
            payment = client.get()
                    .uri("/internal/payments/{bookingId}", bookingId)
                    .retrieve()
                    .body(PaymentStatusView.class);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("No payment for booking {}; rejecting PaymentSucceeded {}", bookingId, paymentId);
            return false;
        }
        boolean ok = payment != null && payment.isSucceeded() && paymentId.equals(payment.paymentId());
        if (!ok) {
            log.warn("payment-service does not confirm booking {} paid under payment {}; rejecting event",
                    bookingId, paymentId);
        }
        return ok;
    }
}
