package com.ticketrush.common.inbox;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ticketrush.common.messaging.IncomingMessage;

/**
 * Idempotent Consumer: records each message id in the handler's own transaction, so a redelivered
 * message is recognised and skipped (NFR-CORR-03). If two deliveries race, the primary key rejects
 * the second commit and the retry then sees the id.
 */
@Component
public class IdempotentConsumer {

    private final ProcessedMessageRepository repository;

    public IdempotentConsumer(ProcessedMessageRepository repository) {
        this.repository = repository;
    }

    /** @return true the first time this message is seen; call it before applying any change */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(IncomingMessage message) {
        if (repository.existsById(message.id())) {
            return false;
        }
        repository.save(new ProcessedMessage(message.id(), message.type(), Instant.now()));
        return true;
    }
}
