package com.ticketrush.event.domain;

import java.time.Instant;
import java.util.List;

import com.ticketrush.contracts.event.SectionSpec;

/**
 * Everything an organizer can edit while the event is still a draft.
 *
 * @param waitingRoom send buyers through the virtual queue; meant for events expected to sell out
 */
public record EventDetails(
        String name, String venue, String city, Instant startsAt, Instant salesOpenAt, List<SectionSpec> sections,
        boolean waitingRoom) {
}
