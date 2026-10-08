package com.ticketrush.contracts.booking;

/** A seat and the price it was sold at. */
public record SeatLine(String seatCode, long priceVnd) {
}
