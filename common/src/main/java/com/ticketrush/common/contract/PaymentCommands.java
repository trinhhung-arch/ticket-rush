package com.ticketrush.common.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * Commands booking-service sends to payment-service on {@code payment.commands}, keyed by booking id
 * so each booking's commands arrive in order.
 */
public final class PaymentCommands {

    public static final int CURRENT_VERSION = 1;

    private PaymentCommands() {
    }

    /** Opens a payment the customer must complete before {@code expiresAt} (FR-PAY-01, FR-PAY-05). */
    public record CreatePayment(int version, UUID bookingId, String userId, long amountVnd, Instant expiresAt) {
    }

    /** The booking was cancelled before the customer paid; the payment must not succeed any more. */
    public record CancelPayment(int version, UUID bookingId, String reason) {
    }

    /** Money arrived for a booking that cannot be honoured, e.g. it expired or its seat was taken (FR-PAY-06). */
    public record RefundPayment(int version, UUID bookingId, String reason) {
    }
}
