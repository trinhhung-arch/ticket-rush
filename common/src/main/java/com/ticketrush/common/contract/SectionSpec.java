package com.ticketrush.common.contract;

/** A block of seats that share a price: {@code rows} x {@code seatsPerRow} seats. */
public record SectionSpec(String code, String name, int rows, int seatsPerRow, long priceVnd) {
}
