package com.ticketrush.payment.domain;

/** Only PENDING can change through the gateway; SUCCEEDED can still become REFUNDED. */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    CANCELLED,
    REFUNDED
}
