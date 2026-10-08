package com.ticketrush.contracts.payment;

import java.util.UUID;

/**
 * payment-service's authoritative view of a booking's payment. The booking saga verifies a
 * {@code PaymentSucceeded} event against this before confirming (FR-PAY-06, NFR-SEC-01), so a forged
 * event on the bus cannot confirm a booking that was never paid. Served on {@code /internal}, which the
 * gateway does not route, so it is reachable only from inside the cluster.
 */
public record PaymentStatusView(UUID bookingId, UUID paymentId, String status) {

    public static final String SUCCEEDED = "SUCCEEDED";

    public boolean isSucceeded() {
        return SUCCEEDED.equals(status);
    }
}
