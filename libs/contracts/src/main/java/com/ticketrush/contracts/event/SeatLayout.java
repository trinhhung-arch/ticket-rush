package com.ticketrush.contracts.event;

import java.util.ArrayList;
import java.util.List;


/**
 * Expands sections into seats. Both event-service and booking-service use it, so the seat codes
 * never have to travel over Kafka and the two sides always agree on them.
 */
public final class SeatLayout {

    private SeatLayout() {
    }

    public static List<Seat> expand(List<SectionSpec> sections) {
        List<Seat> seats = new ArrayList<>(count(sections));
        for (SectionSpec section : sections) {
            for (int row = 1; row <= section.rows(); row++) {
                for (int number = 1; number <= section.seatsPerRow(); number++) {
                    seats.add(new Seat(seatCode(section.code(), row, number), section.code(), section.priceVnd()));
                }
            }
        }
        return seats;
    }

    public static int count(List<SectionSpec> sections) {
        return sections.stream().mapToInt(section -> section.rows() * section.seatsPerRow()).sum();
    }

    /** Section VIP, row 1, seat 1 gives {@code VIP-A-01}. */
    static String seatCode(String sectionCode, int row, int number) {
        return "%s-%s-%02d".formatted(sectionCode, rowLabel(row), number);
    }

    /** 1 gives A, 26 gives Z, 27 gives AA, like spreadsheet columns. */
    static String rowLabel(int row) {
        StringBuilder label = new StringBuilder();
        for (int n = row; n > 0; n = (n - 1) / 26) {
            label.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return label.toString();
    }
}
