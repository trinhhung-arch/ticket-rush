package com.ticketrush.booking.seat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ticketrush.booking.catalog.EventInfoRepository;
import com.ticketrush.web.ApiException;

/** Seat map with live state (FR-BKG-01). The gateway routes /api/events/{id}/seats here. */
@RestController
class SeatMapController {

    enum SeatState { AVAILABLE, HELD, SOLD }

    record SeatView(String code, String sectionCode, long priceVnd, SeatState state) {
    }

    record SeatMapView(UUID eventId, int total, long available, long held, long sold, List<SeatView> seats) {
    }

    private final EventInfoRepository events;
    private final SeatInventory inventory;
    private final SeatHoldStore holds;

    SeatMapController(EventInfoRepository events, SeatInventory inventory, SeatHoldStore holds) {
        this.events = events;
        this.inventory = inventory;
        this.holds = holds;
    }

    @SecurityRequirements // public: no token needed
    @GetMapping("/api/events/{eventId}/seats")
    SeatMapView seatMap(@PathVariable UUID eventId) {
        if (!events.existsById(eventId)) {
            throw ApiException.notFound("Event %s is not on sale".formatted(eventId));
        }
        List<SeatRow> rows = inventory.findAll(eventId);
        Set<String> held = holds.heldAmong(eventId, rows.stream()
                .filter(row -> row.status() == SeatStatus.AVAILABLE).map(SeatRow::code).toList());
        List<SeatView> seats = rows.stream().map(row -> new SeatView(row.code(), row.sectionCode(), row.priceVnd(),
                row.status() == SeatStatus.SOLD ? SeatState.SOLD
                        : held.contains(row.code()) ? SeatState.HELD : SeatState.AVAILABLE)).toList();
        return new SeatMapView(eventId, seats.size(),
                count(seats, SeatState.AVAILABLE), count(seats, SeatState.HELD), count(seats, SeatState.SOLD), seats);
    }

    private static long count(List<SeatView> seats, SeatState state) {
        return seats.stream().filter(seat -> seat.state() == state).count();
    }
}
