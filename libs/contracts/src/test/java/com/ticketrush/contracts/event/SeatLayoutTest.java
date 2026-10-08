package com.ticketrush.contracts.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;


class SeatLayoutTest {

    @Test
    void expandsSectionsRowByRow() {
        List<Seat> seats = SeatLayout.expand(List.of(
                new SectionSpec("VIP", "VIP", 2, 3, 3_000_000),
                new SectionSpec("GA", "Standard", 1, 2, 800_000)));

        assertThat(seats).extracting(Seat::code).containsExactly(
                "VIP-A-01", "VIP-A-02", "VIP-A-03", "VIP-B-01", "VIP-B-02", "VIP-B-03", "GA-A-01", "GA-A-02");
        assertThat(seats).filteredOn(seat -> seat.sectionCode().equals("GA"))
                .extracting(Seat::priceVnd).containsOnly(800_000L);
    }

    @Test
    void labelsRowsLikeSpreadsheetColumns() {
        assertThat(SeatLayout.rowLabel(1)).isEqualTo("A");
        assertThat(SeatLayout.rowLabel(26)).isEqualTo("Z");
        assertThat(SeatLayout.rowLabel(27)).isEqualTo("AA");
        assertThat(SeatLayout.rowLabel(52)).isEqualTo("AZ");
        assertThat(SeatLayout.rowLabel(53)).isEqualTo("BA");
    }

    @Test
    void codesAreUniqueAcrossALargeVenue() {
        List<SectionSpec> sections = List.of(
                new SectionSpec("A", "A", 100, 100, 1), new SectionSpec("B", "B", 100, 100, 1));

        List<Seat> seats = SeatLayout.expand(sections);

        assertThat(seats).hasSize(SeatLayout.count(sections)).extracting(Seat::code).doesNotHaveDuplicates();
    }
}
