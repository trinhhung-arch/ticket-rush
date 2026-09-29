package com.ticketrush.common.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by event-service when an event goes on sale (FR-EVT-02). booking-service builds its
 * seat inventory from {@code sections}, so the seat map is frozen once this is sent.
 */
public record EventPublished(
        int version,
        UUID eventId,
        String name,
        String venue,
        String city,
        Instant startsAt,
        Instant salesOpenAt,
        List<SectionSpec> sections) {

    public static final int CURRENT_VERSION = 1;
}
