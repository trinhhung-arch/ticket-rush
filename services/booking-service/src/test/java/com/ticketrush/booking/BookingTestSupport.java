package com.ticketrush.booking;

import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.TestcontainersConfiguration;
import com.ticketrush.common.contract.EventPublished;
import com.ticketrush.common.contract.SectionSpec;
import com.ticketrush.common.messaging.MessageHeaders;
import com.ticketrush.common.messaging.Topics;

/**
 * Shared Spring context (one set of containers) and helpers for booking-service integration tests.
 * Tracing and metrics are on, as in production, so the observability tests share this context.
 */
@SpringBootTest(properties = "ticketrush.booking.admission-token-key=" + BookingTestSupport.ADMISSION_KEY)
@AutoConfigureMockMvc
@AutoConfigureTracing
@AutoConfigureMetrics
@Import({TestcontainersConfiguration.class, StubPaymentVerifier.Config.class})
abstract class BookingTestSupport {

    /** VIP 2x3 at 3,000,000 VND and GA 2x5 at 800,000 VND: 16 seats. */
    static final List<SectionSpec> SECTIONS = List.of(
            new SectionSpec("VIP", "VIP", 2, 3, 3_000_000),
            new SectionSpec("GA", "Standard", 2, 5, 800_000));

    static final Duration WAIT = Duration.ofSeconds(30);

    static final String ADMISSION_KEY = "test-admission-key-0123456789-0123456789";

    @Autowired
    BookingService bookingService;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JdbcClient jdbc;
    @Autowired
    StubPaymentVerifier paymentVerifier;

    /** Publishes an event the way event-service would and waits until its 16 seats are in the inventory. */
    UUID publishEvent(Instant salesOpenAt) {
        return publishEvent(salesOpenAt, false);
    }

    UUID publishEvent(Instant salesOpenAt, boolean waitingRoom) {
        UUID eventId = UUID.randomUUID();
        send(Topics.EVENT_EVENTS, eventId, UUID.randomUUID(), new EventPublished(EventPublished.CURRENT_VERSION, eventId,
                "Rock Night", "Mỹ Đình", "Hanoi", Instant.now().plus(30, ChronoUnit.DAYS), salesOpenAt, SECTIONS,
                waitingRoom, "organizer-1"));
        await().atMost(WAIT).until(() -> jdbc.sql("select count(*) from seat_inventory where event_id = :id")
                .param("id", eventId).query(Integer.class).single() == 16);
        return eventId;
    }

    UUID publishEventOnSale() {
        return publishEvent(Instant.now().minus(1, ChronoUnit.HOURS));
    }

    /** Sends a message with TicketRush headers; the type header is the contract's class name. */
    void send(String topic, UUID key, UUID messageId, Object message) {
        var record = new ProducerRecord<>(topic, key.toString(), jsonMapper.writeValueAsString(message));
        record.headers()
                .add(MessageHeaders.MESSAGE_ID, messageId.toString().getBytes(StandardCharsets.UTF_8))
                .add(MessageHeaders.MESSAGE_TYPE, message.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    static CreateBooking booking(String user, UUID eventId, String... seats) {
        return new CreateBooking(user, UUID.randomUUID().toString(), eventId, List.of(seats), user + "@example.com", null);
    }

    BookingStatus statusOf(UUID bookingId) {
        return jdbc.sql("select status from booking where id = :id").param("id", bookingId)
                .query(BookingStatus.class).single();
    }

    String cancelReasonOf(UUID bookingId) {
        return jdbc.sql("select cancel_reason from booking where id = :id").param("id", bookingId)
                .query(String.class).single();
    }

    /** Message types the service has written to its outbox for one booking, oldest first. */
    List<String> outboxTypesFor(UUID bookingId) {
        return jdbc.sql("select message_type from outbox_message where message_key = :key order by seq")
                .param("key", bookingId.toString()).query(String.class).list();
    }

    String outboxPayload(UUID bookingId, String messageType) {
        return jdbc.sql("select payload from outbox_message where message_key = :key and message_type = :type")
                .param("key", bookingId.toString()).param("type", messageType).query(String.class).single();
    }

    int bookingsContaining(UUID eventId, String seatCode) {
        return jdbc.sql("""
                        select count(*) from booking b join booking_seat s on s.booking_id = b.id
                        where b.event_id = :eventId and s.seat_code = :seatCode
                        """)
                .param("eventId", eventId).param("seatCode", seatCode)
                .query(Integer.class).single();
    }
}
