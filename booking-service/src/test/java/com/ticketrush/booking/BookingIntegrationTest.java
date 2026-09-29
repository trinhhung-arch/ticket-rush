package com.ticketrush.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.TestcontainersConfiguration;
import com.ticketrush.common.contract.EventPublished;
import com.ticketrush.common.contract.SectionSpec;
import com.ticketrush.common.messaging.MessageHeaders;
import com.ticketrush.common.messaging.Topics;
import com.ticketrush.common.web.ApiException;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BookingIntegrationTest {

    /** VIP 2x3 at 3,000,000 VND and GA 2x5 at 800,000 VND: 16 seats. */
    private static final List<SectionSpec> SECTIONS = List.of(
            new SectionSpec("VIP", "VIP", 2, 3, 3_000_000),
            new SectionSpec("GA", "Standard", 2, 5, 800_000));

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

    /** NFR-TEST-03 / FR-BKG-03. */
    @Test
    void oneThousandCustomersRaceForOneSeatAndExactlyOneWins() {
        UUID eventId = publishEvent(Instant.now().minus(1, ChronoUnit.HOURS));
        int customers = 1_000;
        CountDownLatch startingGun = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < customers; i++) {
                String user = "user-" + i;
                pool.submit(() -> {
                    startingGun.await();
                    try {
                        bookingService.create(booking(user, eventId, "VIP-A-01"));
                        won.incrementAndGet();
                    } catch (ApiException e) {
                        if (e.status() == HttpStatus.CONFLICT) {
                            rejected.incrementAndGet();
                        } else {
                            unexpected.add(e);
                        }
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                    return null;
                });
            }
            startingGun.countDown();
        }

        assertThat(unexpected).isEmpty();
        assertThat(won).hasValue(1);
        assertThat(rejected).hasValue(customers - 1);
        assertThat(bookingsContaining(eventId, "VIP-A-01")).isEqualTo(1);
    }

    /** FR-BKG-02: a request that cannot get every seat gets none of them. */
    @Test
    void holdsAreAllOrNothing() {
        UUID eventId = publishEvent(Instant.now().minus(1, ChronoUnit.HOURS));
        bookingService.create(booking("alice", eventId, "GA-B-01"));

        assertThatThrownBy(() -> bookingService.create(booking("bob", eventId, "GA-B-02", "GA-B-01")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.properties()).containsEntry("seatCode", "GA-B-01");
                });

        BookingService.Result carol = bookingService.create(booking("carol", eventId, "GA-B-02"));
        assertThat(carol.booking().seats()).extracting(BookingSeat::seatCode).containsExactly("GA-B-02");
    }

    /** FR-BKG-04: replaying the same Idempotency-Key returns the first booking instead of a second one. */
    @Test
    void replayingAnIdempotencyKeyReturnsTheSameBooking() {
        UUID eventId = publishEvent(Instant.now().minus(1, ChronoUnit.HOURS));
        String body = """
                {"eventId":"%s","seatCodes":["GA-A-01","GA-A-02"],"email":"an@example.com"}
                """.formatted(eventId);

        MvcTestResult first = postBooking("an", "key-" + eventId, body);
        assertThat(first).hasStatus(HttpStatus.CREATED).bodyJson()
                .hasPathSatisfying("$.status", status -> status.assertThat().isEqualTo("PENDING"))
                .hasPathSatisfying("$.totalVnd", total -> total.assertThat().isEqualTo(1_600_000));
        String bookingId = JsonPath.read(content(first), "$.id");

        assertThat(postBooking("an", "key-" + eventId, body))
                .hasStatusOk().bodyJson().extractingPath("$.id").isEqualTo(bookingId);
        assertThat(bookingsContaining(eventId, "GA-A-01")).isEqualTo(1);

        assertThat(mvc.post().uri("/api/bookings").header("X-User-Id", "an")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .as("missing Idempotency-Key").hasStatus(HttpStatus.BAD_REQUEST);
    }

    /** FR-BKG-01: held seats show up as HELD without being written to the database. */
    @Test
    void seatMapShowsLiveHolds() {
        UUID eventId = publishEvent(Instant.now().minus(1, ChronoUnit.HOURS));
        bookingService.create(booking("dave", eventId, "VIP-B-01", "VIP-B-02"));

        assertThat(mvc.get().uri("/api/events/{id}/seats", eventId)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.total", total -> total.assertThat().isEqualTo(16))
                .hasPathSatisfying("$.held", held -> held.assertThat().isEqualTo(2))
                .hasPathSatisfying("$.available", available -> available.assertThat().isEqualTo(14))
                .hasPathSatisfying("$.seats[?(@.code == 'VIP-B-01')].state",
                        state -> state.assertThat().asArray().containsExactly("HELD"));
    }

    @Test
    void rejectsUnknownSeatsAndEventsNotOnSale() {
        UUID onSale = publishEvent(Instant.now().minus(1, ChronoUnit.HOURS));
        assertThatThrownBy(() -> bookingService.create(booking("erin", onSale, "VIP-Z-99")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));

        UUID notYetOnSale = publishEvent(Instant.now().plus(1, ChronoUnit.DAYS));
        assertThatThrownBy(() -> bookingService.create(booking("erin", notYetOnSale, "VIP-A-01")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    private UUID publishEvent(Instant salesOpenAt) {
        UUID eventId = UUID.randomUUID();
        var event = new EventPublished(EventPublished.CURRENT_VERSION, eventId, "Rock Night", "Mỹ Đình", "Hanoi",
                Instant.now().plus(30, ChronoUnit.DAYS), salesOpenAt, SECTIONS);
        var record = new ProducerRecord<>(Topics.EVENT_EVENTS, eventId.toString(), jsonMapper.writeValueAsString(event));
        record.headers()
                .add(MessageHeaders.MESSAGE_ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                .add(MessageHeaders.MESSAGE_TYPE, "EventPublished".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc
                .sql("select count(*) from seat_inventory where event_id = :id").param("id", eventId)
                .query(Integer.class).single() == 16);
        return eventId;
    }

    private static CreateBooking booking(String user, UUID eventId, String... seats) {
        return new CreateBooking(user, UUID.randomUUID().toString(), eventId, List.of(seats), user + "@example.com");
    }

    private MvcTestResult postBooking(String user, String idempotencyKey, String body) {
        return mvc.post().uri("/api/bookings").header("X-User-Id", user).header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private int bookingsContaining(UUID eventId, String seatCode) {
        return jdbc.sql("""
                        select count(*) from booking b join booking_seat s on s.booking_id = b.id
                        where b.event_id = :eventId and s.seat_code = :seatCode
                        """)
                .param("eventId", eventId).param("seatCode", seatCode)
                .query(Integer.class).single();
    }

    private static String content(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
