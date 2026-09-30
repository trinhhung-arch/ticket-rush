package com.ticketrush.common.outbox;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A message written in the same transaction as the state change it announces (Transactional Outbox). */
@Entity
@Table(name = "outbox_message")
public class OutboxMessage {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String topic;

    @Column(name = "message_key", nullable = false)
    private String messageKey;

    @Column(name = "message_type", nullable = false)
    private String messageType;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** W3C traceparent of the transaction that wrote the message, so the trace continues through Kafka. */
    @Column(name = "trace_parent")
    private String traceParent;

    protected OutboxMessage() {
    }

    OutboxMessage(String topic, String messageKey, String messageType, String payload, Instant createdAt,
                  String traceParent) {
        this.id = UUID.randomUUID();
        this.topic = topic;
        this.messageKey = messageKey;
        this.messageType = messageType;
        this.payload = payload;
        this.createdAt = createdAt;
        this.traceParent = traceParent;
    }

    void markPublished(Instant at) {
        this.publishedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getTraceParent() {
        return traceParent;
    }
}
