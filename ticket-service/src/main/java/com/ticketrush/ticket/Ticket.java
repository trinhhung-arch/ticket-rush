package com.ticketrush.ticket;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One entry pass for one seat. */
@Entity
@Table(name = "ticket")
public class Ticket {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "seat_code", nullable = false)
    private String seatCode;

    @Column(name = "event_name", nullable = false)
    private String eventName;

    @Column(nullable = false)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "qr_token", nullable = false, unique = true)
    private String qrToken;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    protected Ticket() {
    }

    Ticket(UUID id, UUID bookingId, UUID eventId, String userId, String seatCode, String eventName, String venue,
           Instant startsAt, String qrToken, Instant issuedAt) {
        this.id = id;
        this.bookingId = bookingId;
        this.eventId = eventId;
        this.userId = userId;
        this.seatCode = seatCode;
        this.eventName = eventName;
        this.venue = venue;
        this.startsAt = startsAt;
        this.qrToken = qrToken;
        this.issuedAt = issuedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID bookingId() {
        return bookingId;
    }

    public UUID eventId() {
        return eventId;
    }

    public String seatCode() {
        return seatCode;
    }

    public String eventName() {
        return eventName;
    }

    public String venue() {
        return venue;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public String qrToken() {
        return qrToken;
    }

    public Instant checkedInAt() {
        return checkedInAt;
    }
}
