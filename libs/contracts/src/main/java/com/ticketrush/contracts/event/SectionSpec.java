package com.ticketrush.contracts.event;

/** A block of seats that share a price: {@code rows} x {@code seatsPerRow} seats. */
public record SectionSpec(String code, String name, int rows, int seatsPerRow, long priceVnd) {
}
