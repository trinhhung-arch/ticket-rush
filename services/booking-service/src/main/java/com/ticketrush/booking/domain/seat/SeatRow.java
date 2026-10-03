package com.ticketrush.booking.domain.seat;

public record SeatRow(String code, String sectionCode, long priceVnd, SeatStatus status) {
}
