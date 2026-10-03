package com.ticketrush.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;

import com.ticketrush.common.messaging.Topics;
import com.ticketrush.common.web.ApiException;

/** NFR-OBS-01 and NFR-OBS-02 at the service level; the dashboards themselves are checked with Docker Compose. */
class ObservabilityIntegrationTest extends BookingTestSupport {

    @Autowired
    Tracer tracer;

    @Autowired
    KafkaConnectionDetails kafka;

    /** The outbox stores the request's trace, and the relay's Kafka record carries it to the next service. */
    @Test
    void aBookingRequestsTraceContinuesThroughTheOutboxIntoKafka() {
        UUID eventId = publishEventOnSale();
        Span request = tracer.nextSpan().name("POST /api/bookings").start();
        UUID bookingId;
        try (Tracer.SpanInScope scope = tracer.withSpan(request)) {
            bookingId = bookingService.create(booking("tracy", eventId, "GA-A-04")).booking().id();
        } finally {
            request.end();
        }
        String traceId = request.context().traceId();

        String stored = jdbc.sql("select trace_parent from outbox_message where message_key = :key")
                .param("key", bookingId.toString()).query(String.class).single();
        assertThat(stored).startsWith("00-" + traceId + "-");

        ConsumerRecord<String, String> command = firstRecord(Topics.PAYMENT_COMMANDS, bookingId);
        Header traceparent = command.headers().lastHeader("traceparent");
        assertThat(traceparent).as("traceparent header on the CreatePayment record").isNotNull();
        assertThat(new String(traceparent.value(), StandardCharsets.UTF_8)).startsWith("00-" + traceId + "-");
    }

    @Test
    void businessMetricsAreExposedToPrometheus() {
        UUID eventId = publishEventOnSale();
        bookingService.create(booking("mai", eventId, "GA-B-04"));
        assertThatThrownBy(() -> bookingService.create(booking("nam", eventId, "GA-B-04")))
                .isInstanceOf(ApiException.class);

        assertThat(mvc.get().uri("/actuator/prometheus")).hasStatusOk().bodyText()
                .contains("ticketrush_seat_holds_total{", "result=\"held\"", "result=\"conflict\"")
                .contains("ticketrush_outbox_pending", "ticketrush_outbox_lag_seconds")
                .contains("ticketrush_saga_confirmation_seconds_bucket")
                .contains("application=\"booking-service\"");
    }

    private ConsumerRecord<String, String> firstRecord(String topic, UUID key) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafka.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "observability-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(WAIT);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.toString().equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No record for " + key + " on " + topic);
    }
}
