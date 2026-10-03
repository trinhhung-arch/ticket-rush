package com.ticketrush.common.contract;

/** A seat and the price it was sold at. */
public record SeatLine(String seatCode, long priceVnd) {
}
