package com.ticketrush.booking;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Stands in for payment-service in booking-service tests: a booking counts as paid only when a test has
 * marked it so, mirroring a real SUCCEEDED payment. A PaymentSucceeded event for a booking that was never
 * marked is treated as forged, which is what {@code forgedPaymentSucceededIsRejected} relies on.
 */
class StubPaymentVerifier implements PaymentVerifier {

    private final ConcurrentHashMap<UUID, UUID> paid = new ConcurrentHashMap<>();

    void markPaid(UUID bookingId, UUID paymentId) {
        paid.put(bookingId, paymentId);
    }

    @Override
    public boolean confirmsPaid(UUID bookingId, UUID paymentId) {
        return paymentId.equals(paid.get(bookingId));
    }

    @TestConfiguration
    static class Config {

        @Bean
        @Primary
        StubPaymentVerifier stubPaymentVerifier() {
            return new StubPaymentVerifier();
        }
    }
}
