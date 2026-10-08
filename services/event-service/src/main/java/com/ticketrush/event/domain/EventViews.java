package com.ticketrush.event.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ticketrush.contracts.event.SectionSpec;

/** Response bodies of the event API. */
public final class EventViews {

    private EventViews() {
    }

    public record EventView(
            UUID id,
            String organizerId,
            String name,
            String venue,
            String city,
            Instant startsAt,
            Instant salesOpenAt,
            EventStatus status,
            int totalSeats,
            boolean waitingRoom,
            List<SectionView> sections) {

        static EventView of(Event event) {
            return new EventView(event.id(), event.organizerId(), event.name(), event.venue(), event.city(),
                    event.startsAt(), event.salesOpenAt(), event.status(), event.totalSeats(), event.waitingRoom(),
                    event.sectionSpecs().stream().map(SectionView::of).toList());
        }
    }

    public record SectionView(String code, String name, int rows, int seatsPerRow, int seats, long priceVnd) {

        static SectionView of(SectionSpec spec) {
            return new SectionView(spec.code(), spec.name(), spec.rows(), spec.seatsPerRow(),
                    spec.rows() * spec.seatsPerRow(), spec.priceVnd());
        }
    }

    public record EventSummary(
            UUID id, String name, String venue, String city, Instant startsAt, Instant salesOpenAt,
            int totalSeats, long minPriceVnd) {

        static EventSummary of(Event event) {
            long minPrice = event.sectionSpecs().stream().mapToLong(SectionSpec::priceVnd).min().orElse(0);
            return new EventSummary(event.id(), event.name(), event.venue(), event.city(), event.startsAt(),
                    event.salesOpenAt(), event.totalSeats(), minPrice);
        }
    }
}
