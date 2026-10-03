package com.ticketrush.booking.catalog;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.ticketrush.contracts.event.EventPublished;
import com.ticketrush.contracts.event.SeatLayout;

/** booking-service's own copy of a published event, built from {@link EventPublished}. */
@Entity
@Table(name = "event_info")
public class EventInfo {

    @Id
    private UUID id;

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

    @Column(name = "total_seats", nullable = false)
    private int totalSeats;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "waiting_room", nullable = false)
    private boolean waitingRoom;

    protected EventInfo() {
    }

    static EventInfo from(EventPublished event, Instant now) {
        EventInfo info = new EventInfo();
        info.id = event.eventId();
        info.name = event.name();
        info.venue = event.venue();
        info.city = event.city();
        info.startsAt = event.startsAt();
        info.salesOpenAt = event.salesOpenAt();
        info.totalSeats = SeatLayout.count(event.sections());
        info.receivedAt = now;
        info.waitingRoom = event.waitingRoom();
        return info;
    }

    public boolean isOnSale(Instant now) {
        return !now.isBefore(salesOpenAt) && now.isBefore(startsAt);
    }

    public UUID id() {
        return id;
    }

    /** Buyers need an admission token from the waiting room (FR-WR-03). */
    public boolean waitingRoom() {
        return waitingRoom;
    }

    public String name() {
        return name;
    }

    public String venue() {
        return venue;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant salesOpenAt() {
        return salesOpenAt;
    }
}
