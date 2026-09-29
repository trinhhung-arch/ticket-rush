package com.ticketrush.event;

import java.time.Instant;
import java.util.List;

import com.ticketrush.common.contract.SectionSpec;

/** Everything an organizer can edit while the event is still a draft. */
public record EventDetails(
        String name, String venue, String city, Instant startsAt, Instant salesOpenAt, List<SectionSpec> sections) {
}
