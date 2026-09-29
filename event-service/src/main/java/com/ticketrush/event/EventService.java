package com.ticketrush.event;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketrush.common.contract.SectionSpec;
import com.ticketrush.common.messaging.Topics;
import com.ticketrush.common.outbox.OutboxWriter;
import com.ticketrush.common.seat.SeatLayout;
import com.ticketrush.common.web.ApiException;
import com.ticketrush.event.EventViews.EventSummary;
import com.ticketrush.event.EventViews.EventView;

@Service
class EventService {

    /** Upper bound agreed in the requirements (assumptions section). */
    static final int MAX_SEATS_PER_EVENT = 20_000;

    /** The platform sells in one time zone only; date filters are local dates there. */
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final EventRepository events;
    private final OutboxWriter outbox;

    EventService(EventRepository events, OutboxWriter outbox) {
        this.events = events;
        this.outbox = outbox;
    }

    @Transactional
    public EventView create(String organizerId, EventDetails details) {
        validate(details);
        return EventView.of(events.save(Event.draft(organizerId, details, Instant.now())));
    }

    @Transactional
    public EventView update(UUID id, String userId, EventDetails details) {
        Event event = ownedEvent(id, userId);
        if (!event.isDraft()) {
            throw ApiException.conflict("Event %s is published; its seat map is locked".formatted(id));
        }
        validate(details);
        event.update(details);
        return EventView.of(event);
    }

    /** Publishing twice is a no-op, so a retried request never announces the event twice. */
    @Transactional
    public EventView publish(UUID id, String userId) {
        Event event = ownedEvent(id, userId);
        if (event.publish(Instant.now())) {
            outbox.append(Topics.EVENT_EVENTS, id.toString(), event.toPublishedMessage());
        }
        return EventView.of(event);
    }

    @Transactional(readOnly = true)
    public EventView get(UUID id, String userId) {
        Event event = events.findById(id)
                .filter(e -> !e.isDraft() || e.isOwnedBy(userId))
                .orElseThrow(() -> ApiException.notFound("Event %s not found".formatted(id)));
        return EventView.of(event);
    }

    /** Lists published events only (FR-EVT-03); {@code from} and {@code to} are inclusive local dates. */
    @Transactional(readOnly = true)
    public Page<EventSummary> search(String city, LocalDate from, LocalDate to, Pageable pageable) {
        Specification<Event> spec = (root, query, cb) -> cb.equal(root.get("status"), EventStatus.PUBLISHED);
        if (city != null && !city.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(cb.lower(root.get("city")), city.toLowerCase()));
        }
        if (from != null) {
            Instant start = from.atStartOfDay(ZONE).toInstant();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startsAt"), start));
        }
        if (to != null) {
            Instant end = to.plusDays(1).atStartOfDay(ZONE).toInstant();
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("startsAt"), end));
        }
        return events.findAll(spec, pageable).map(EventSummary::of);
    }

    private Event ownedEvent(UUID id, String userId) {
        Event event = events.findById(id).orElseThrow(() -> ApiException.notFound("Event %s not found".formatted(id)));
        if (!event.isOwnedBy(userId)) {
            throw ApiException.forbidden("Only the organizer of event %s can change it".formatted(id));
        }
        return event;
    }

    private static void validate(EventDetails details) {
        if (!details.salesOpenAt().isBefore(details.startsAt())) {
            throw ApiException.badRequest("salesOpenAt must be before startsAt");
        }
        Set<String> codes = new HashSet<>();
        for (SectionSpec section : details.sections()) {
            if (!codes.add(section.code())) {
                throw ApiException.badRequest("Section code %s is used twice".formatted(section.code()));
            }
        }
        int seats = SeatLayout.count(details.sections());
        if (seats > MAX_SEATS_PER_EVENT) {
            throw ApiException.badRequest("An event can have at most %d seats, got %d".formatted(MAX_SEATS_PER_EVENT, seats));
        }
    }
}
