package com.ticketrush.payment.domain;

import java.time.Instant;
import java.util.UUID;

public record PaymentView(
        UUID id, UUID bookingId, long amountVnd, PaymentStatus status, String statusReason, Instant expiresAt,
        Instant updatedAt) {

    static PaymentView of(Payment payment) {
        return new PaymentView(payment.id(), payment.bookingId(), payment.amountVnd(), payment.status(),
                payment.statusReason(), payment.expiresAt(), payment.updatedAt());
    }
}
