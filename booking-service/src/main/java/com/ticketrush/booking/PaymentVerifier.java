package com.ticketrush.booking;

import java.util.UUID;

/**
 * Checks a {@code PaymentSucceeded} event against payment-service's authoritative record before the saga
 * confirms a booking (FR-PAY-06, NFR-SEC-01). Without this, anyone who can put a message on the bus could
 * confirm an unpaid booking and be issued a ticket; the event is treated as a claim, not proof.
 */
interface PaymentVerifier {

    /**
     * @return true only when payment-service reports this booking's payment as SUCCEEDED with this id.
     * @throws RuntimeException when payment-service cannot be reached, so the event is retried rather than
     *         dropped or trusted.
     */
    boolean confirmsPaid(UUID bookingId, UUID paymentId);
}
