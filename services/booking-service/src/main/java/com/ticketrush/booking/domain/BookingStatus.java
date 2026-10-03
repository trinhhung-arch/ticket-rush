package com.ticketrush.booking.domain;

/** Booking saga states; CONFIRMED and CANCELLED are final. */
public enum BookingStatus {
    /** Seats held, payment not requested yet. */
    PENDING,
    /** Payment created, waiting for the customer to pay. */
    AWAITING_PAYMENT,
    CONFIRMED,
    CANCELLED
}
