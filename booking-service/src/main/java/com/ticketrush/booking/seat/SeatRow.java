package com.ticketrush.booking.seat;

public record SeatRow(String code, String sectionCode, long priceVnd, SeatStatus status) {
}
