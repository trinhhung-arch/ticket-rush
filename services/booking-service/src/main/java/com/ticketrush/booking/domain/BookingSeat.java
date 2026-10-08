package com.ticketrush.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** A seat in a booking, with the price it was sold at. */
@Embeddable
public record BookingSeat(
        @Column(name = "seat_code", nullable = false) String seatCode,
        @Column(name = "price_vnd", nullable = false) long priceVnd) {
}
