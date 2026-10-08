package com.ticketrush.contracts.payment;

import java.time.Instant;
import java.util.UUID;

/** Events payment-service publishes on {@code payment.events}, keyed by booking id. */
public final class PaymentEvents {

    public static final int CURRENT_VERSION = 1;

    private PaymentEvents() {
    }

    public record PaymentCreated(int version, UUID paymentId, UUID bookingId, String checkoutUrl, Instant expiresAt) {
    }

    public record PaymentSucceeded(int version, UUID paymentId, UUID bookingId, long amountVnd, Instant paidAt) {
    }

    /** @param reason DECLINED by the gateway, or EXPIRED when the customer paid after the hold ended */
    public record PaymentFailed(int version, UUID paymentId, UUID bookingId, String reason) {
    }

    public record PaymentRefunded(int version, UUID paymentId, UUID bookingId, long amountVnd, String reason) {
    }
}
