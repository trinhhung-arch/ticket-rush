package com.ticketrush.common.outbox;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Services never call Kafka directly: they append to the outbox and {@link OutboxRelay} publishes after commit. */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;

    public OutboxWriter(OutboxRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Stores {@code message} in the caller's transaction. The message type is the contract's class name.
     *
     * @param key Kafka record key; use the aggregate id so one aggregate's messages stay in order (NFR-CORR-06)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String key, Object message) {
        repository.save(new OutboxMessage(
                topic, key, message.getClass().getSimpleName(), jsonMapper.writeValueAsString(message), Instant.now()));
    }
}
