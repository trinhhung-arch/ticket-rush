package com.ticketrush.messaging.outbox;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Services never call Kafka directly: they append to the outbox and {@link OutboxRelay} publishes after commit. */
public class OutboxWriter {

    static final String TRACEPARENT = "traceparent";

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxWriter(OutboxRepository repository, JsonMapper jsonMapper, Tracer tracer,
                        ObjectProvider<Propagator> propagator) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.tracer = tracer;
        // No propagator when tracing is switched off (tests); then nothing is carried over.
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    }

    /**
     * Stores {@code message} in the caller's transaction. The message type is the contract's class name.
     *
     * @param key Kafka record key; use the aggregate id so one aggregate's messages stay in order (NFR-CORR-06)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String key, Object message) {
        repository.save(new OutboxMessage(topic, key, message.getClass().getSimpleName(),
                jsonMapper.writeValueAsString(message), Instant.now(), currentTraceParent()));
    }

    /** The request or message being handled right now, as a W3C traceparent (NFR-OBS-01). */
    private String currentTraceParent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }
}
