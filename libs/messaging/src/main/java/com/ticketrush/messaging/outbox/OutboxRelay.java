package com.ticketrush.messaging.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketrush.contracts.MessageHeaders;

/**
 * Polling publisher: sends unpublished outbox rows to Kafka and marks them published.
 * A crash between send and commit re-sends the batch, so delivery is at-least-once and
 * every consumer must be idempotent (see {@code IdempotentConsumer}).
 */
@ConditionalOnProperty(name = "ticketrush.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Tracer tracer;
    private final Propagator propagator;
    private final int batchSize;

    public OutboxRelay(OutboxRepository repository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       PlatformTransactionManager transactionManager,
                       Tracer tracer,
                       ObjectProvider<Propagator> propagator,
                       @Value("${ticketrush.outbox.relay.batch-size:200}") int batchSize) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.tracer = tracer;
        // No propagator when tracing is switched off (tests); then nothing is carried over.
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ticketrush.outbox.relay.interval-ms:200}")
    public void relay() {
        try {
            Integer published;
            do {
                published = transactionTemplate.execute(status -> publishNextBatch());
            } while (published != null && published == batchSize);
        } catch (RuntimeException e) {
            log.warn("Outbox relay failed, the batch stays unpublished and will be retried: {}", e.toString());
        }
    }

    private int publishNextBatch() {
        List<OutboxMessage> batch = repository.lockNextBatch(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        CompletableFuture<?>[] sends = batch.stream().map(this::send).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(sends).orTimeout(10, TimeUnit.SECONDS).join();
        Instant now = Instant.now();
        batch.forEach(message -> message.markPublished(now));
        return batch.size();
    }

    /**
     * Sends inside a span that continues the trace stored with the message, so KafkaTemplate's producer
     * span, and the consumer on the other side, join the original request's trace (NFR-OBS-01).
     */
    private CompletableFuture<?> send(OutboxMessage message) {
        var record = new ProducerRecord<>(message.getTopic(), message.getMessageKey(), message.getPayload());
        record.headers()
                .add(MessageHeaders.MESSAGE_ID, message.getId().toString().getBytes(StandardCharsets.UTF_8))
                .add(MessageHeaders.MESSAGE_TYPE, message.getMessageType().getBytes(StandardCharsets.UTF_8));
        Span span = publishSpan(message);
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            return kafkaTemplate.send(record);
        } finally {
            span.end();
        }
    }

    private Span publishSpan(OutboxMessage message) {
        Span.Builder builder = message.getTraceParent() == null
                ? tracer.spanBuilder()
                : propagator.extract(Map.of(OutboxWriter.TRACEPARENT, message.getTraceParent()), Map::get);
        return builder.name("outbox publish " + message.getMessageType())
                .tag("messaging.destination.name", message.getTopic())
                .tag("messaging.message.id", message.getId().toString())
                .start();
    }

    /** Published rows are only kept for a day, for debugging. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void purgePublished() {
        Integer deleted = transactionTemplate.execute(
                status -> repository.deletePublishedBefore(Instant.now().minus(Duration.ofDays(1))));
        log.debug("Purged {} published outbox messages", deleted);
    }
}
