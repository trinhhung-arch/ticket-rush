package com.ticketrush.contracts.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by event-service when an event goes on sale (FR-EVT-02). booking-service builds its
 * seat inventory from {@code sections}, so the seat map is frozen once this is sent.
 *
 * @param waitingRoom buyers need an admission token from the waiting room (FR-WR-03); absent in
 *                    older messages, which then read as false
 * @param organizerId the account that runs the event; ticket-service lets only them (or an admin)
 *                    check its tickets in (FR-TKT-03). Absent (null) in older messages
 */
public record EventPublished(
        int version,
        UUID eventId,
        String name,
        String venue,
        String city,
        Instant startsAt,
        Instant salesOpenAt,
        List<SectionSpec> sections,
        boolean waitingRoom,
        String organizerId) {

    public static final int CURRENT_VERSION = 1;
}
