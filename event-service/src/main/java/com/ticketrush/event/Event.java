package com.ticketrush.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import com.ticketrush.common.contract.EventPublished;
import com.ticketrush.common.contract.SectionSpec;
import com.ticketrush.common.seat.SeatLayout;

@Entity
@Table(name = "event")
public class Event {

    @Id
    private UUID id;

    @Column(name = "organizer_id", nullable = false)
    private String organizerId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String venue;

    @Column(nullable = false)
    private String city;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "sales_open_at", nullable = false)
    private Instant salesOpenAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "waiting_room", nullable = false)
    private boolean waitingRoom;

    @Version
    private Long version;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    @BatchSize(size = 50)
    private List<Section> sections = new ArrayList<>();

    protected Event() {
    }

    static Event draft(String organizerId, EventDetails details, Instant now) {
        Event event = new Event();
        event.id = UUID.randomUUID();
        event.organizerId = organizerId;
        event.status = EventStatus.DRAFT;
        event.createdAt = now;
        event.apply(details);
        return event;
    }

    void update(EventDetails details) {
        if (status != EventStatus.DRAFT) {
            throw new IllegalStateException("Only draft events can change");
        }
        apply(details);
    }

    /** @return false when the event was already published, so callers can stay idempotent */
    boolean publish(Instant now) {
        if (status == EventStatus.PUBLISHED) {
            return false;
        }
        status = EventStatus.PUBLISHED;
        publishedAt = now;
        return true;
    }

    EventPublished toPublishedMessage() {
        return new EventPublished(
                EventPublished.CURRENT_VERSION, id, name, venue, city, startsAt, salesOpenAt, sectionSpecs(), waitingRoom);
    }

    private void apply(EventDetails details) {
        this.name = details.name();
        this.venue = details.venue();
        this.city = details.city();
        this.startsAt = details.startsAt();
        this.salesOpenAt = details.salesOpenAt();
        this.waitingRoom = details.waitingRoom();
        replaceSections(details.sections());
    }

    /**
     * Updates sections in place, matched by code. Clearing and re-adding would make Hibernate insert
     * the new rows before deleting the old ones and trip the (event_id, code) unique key.
     */
    private void replaceSections(List<SectionSpec> specs) {
        Map<String, Section> existing = sections.stream().collect(Collectors.toMap(Section::code, Function.identity()));
        List<Section> updated = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            SectionSpec spec = specs.get(i);
            Section section = existing.containsKey(spec.code()) ? existing.get(spec.code()) : new Section(this, spec.code());
            section.apply(spec, i);
            updated.add(section);
        }
        sections.clear();
        sections.addAll(updated);
    }

    public UUID id() {
        return id;
    }

    public String organizerId() {
        return organizerId;
    }

    public String name() {
        return name;
    }

    public String venue() {
        return venue;
    }

    public String city() {
        return city;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant salesOpenAt() {
        return salesOpenAt;
    }

    public EventStatus status() {
        return status;
    }

    public boolean waitingRoom() {
        return waitingRoom;
    }

    public boolean isDraft() {
        return status == EventStatus.DRAFT;
    }

    public boolean isOwnedBy(String userId) {
        return organizerId.equals(userId);
    }

    public List<SectionSpec> sectionSpecs() {
        return sections.stream().map(Section::toSpec).toList();
    }

    public int totalSeats() {
        return SeatLayout.count(sectionSpecs());
    }
}
