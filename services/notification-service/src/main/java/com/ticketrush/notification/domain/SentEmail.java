package com.ticketrush.notification.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Log of emails sent, one per booking and kind, which also stops the same email going out twice. */
@Entity
@Table(name = "sent_email")
public class SentEmail {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected SentEmail() {
    }

    SentEmail(UUID bookingId, String kind, String recipient, String subject, Instant sentAt) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.kind = kind;
        this.recipient = recipient;
        this.subject = subject;
        this.sentAt = sentAt;
    }
}
