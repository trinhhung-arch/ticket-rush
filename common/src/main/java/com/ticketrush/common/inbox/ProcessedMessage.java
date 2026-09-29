package com.ticketrush.common.inbox;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** Id of a message this service has already handled. */
@Entity
@Table(name = "processed_message")
public class ProcessedMessage implements Persistable<UUID> {

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "message_type", nullable = false)
    private String messageType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean isNew = true;

    protected ProcessedMessage() {
    }

    ProcessedMessage(UUID messageId, String messageType, Instant processedAt) {
        this.messageId = messageId;
        this.messageType = messageType;
        this.processedAt = processedAt;
    }

    @Override
    public UUID getId() {
        return messageId;
    }

    /** Lets save() insert straight away instead of selecting first. */
    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
