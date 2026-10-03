package com.ticketrush.messaging.outbox;

import java.time.Duration;
import java.time.Instant;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * How far the outbox relay is behind: messages waiting and the age of the oldest one. A growing lag
 * means Kafka or the relay is stuck (NFR-OBS-02, alert in NFR-OBS-04). Read on every scrape.
 */
class OutboxMetrics implements MeterBinder {

    private final OutboxRepository repository;

    OutboxMetrics(OutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("ticketrush.outbox.pending", repository, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox messages not yet published to Kafka")
                .register(registry);
        Gauge.builder("ticketrush.outbox.lag", repository, OutboxMetrics::oldestAgeSeconds)
                .description("Age of the oldest unpublished outbox message")
                .baseUnit("seconds")
                .register(registry);
    }

    private static double oldestAgeSeconds(OutboxRepository repository) {
        Instant oldest = repository.oldestUnpublishedCreatedAt();
        return oldest == null ? 0 : Duration.between(oldest, Instant.now()).toMillis() / 1000.0;
    }
}
