package com.ticketrush.ticket;

import static com.ticketrush.security.TestJwts.admin;
import static com.ticketrush.security.TestJwts.customer;
import static com.ticketrush.security.TestJwts.organizer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.TestcontainersConfiguration;
import com.ticketrush.contracts.MessageHeaders;
import com.ticketrush.contracts.Topics;
import com.ticketrush.contracts.booking.BookingEvents;
import com.ticketrush.contracts.booking.BookingEvents.BookingConfirmed;
import com.ticketrush.contracts.booking.SeatLine;
import com.ticketrush.contracts.event.EventPublished;
import com.ticketrush.contracts.event.SectionSpec;
import com.ticketrush.security.Caller;
import com.ticketrush.security.Roles;
import com.ticketrush.web.ApiException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TicketIntegrationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TicketTokens tokens;

    @Autowired
    CheckIn checkIn;

    /** FR-TKT-01: one signed ticket per seat, issued once even if the confirmation is delivered again. */
    @Test
    void issuesOneSignedTicketPerSeatOnce() {
        UUID bookingId = UUID.randomUUID();
        BookingConfirmed confirmed = confirmed(bookingId, "an", "VIP-A-01", "VIP-A-02", "VIP-A-03");
        send(bookingId, UUID.randomUUID(), confirmed);
        send(bookingId, UUID.randomUUID(), confirmed);

        await().atMost(Duration.ofSeconds(30)).until(() -> ticketsFor(bookingId) == 3);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).until(() -> ticketsFor(bookingId) == 3);

        String issued = jdbc.sql("select payload from outbox_message where message_key = :key and message_type = 'TicketsIssued'")
                .param("key", bookingId.toString()).query(String.class).single();
        List<String> qrTokens = JsonPath.read(issued, "$.tickets[*].qrToken");
        assertThat(qrTokens).hasSize(3).allSatisfy(token -> assertThat(tokens.verify(token)).isPresent());
        assertThat((String) JsonPath.read(issued, "$.email")).isEqualTo("an@example.com");
    }

    /** FR-TKT-02: customers only ever see their own tickets. */
    @Test
    void customersSeeOnlyTheirOwnTickets() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        String user = "binh-" + UUID.randomUUID();
        send(mine, UUID.randomUUID(), confirmed(mine, user, "GA-A-01", "GA-A-02"));
        send(theirs, UUID.randomUUID(), confirmed(theirs, "chi", "GA-A-03"));
        await().atMost(Duration.ofSeconds(30)).until(() -> ticketsFor(mine) == 2 && ticketsFor(theirs) == 1);

        assertThat(mvc.get().uri("/api/tickets").with(customer(user))).hasStatusOk().bodyJson()
                .extractingPath("$[*].seatCode").asArray().containsExactly("GA-A-01", "GA-A-02");
        assertThat(mvc.get().uri("/api/tickets").param("bookingId", theirs.toString()).with(customer(user)))
                .hasStatusOk().bodyJson().extractingPath("$").asArray().isEmpty();
    }

    /** FR-TKT-03: only the event's organizer (or an admin) admits a ticket, and only once. */
    @Test
    void aTicketGetsInOnceAndOnlyThroughItsOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        publishEvent(eventId, "olivia");
        send(bookingId, UUID.randomUUID(), confirmed(bookingId, eventId, "an", "VIP-A-01", "VIP-A-02"));
        await().atMost(Duration.ofSeconds(30)).until(() -> ticketsFor(bookingId) == 2
                && jdbc.sql("select count(*) from event_organizer where event_id = :id").param("id", eventId)
                .query(Integer.class).single() == 1);
        List<String> qr = qrTokens(bookingId);

        assertThat(scan(customer("an"), eventId, qr.get(0))).as("customer").hasStatus(HttpStatus.FORBIDDEN);
        assertThat(scan(organizer("oscar"), eventId, qr.get(0))).as("another event's organizer")
                .hasStatus(HttpStatus.FORBIDDEN);
        String forged = qr.get(0).substring(0, qr.get(0).lastIndexOf('.') + 1) + "AAAA";
        assertThat(scan(organizer("olivia"), eventId, forged)).as("forged QR")
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson().extractingPath("$.result").isEqualTo("INVALID");
        assertThat(scan(organizer("olivia"), UUID.randomUUID(), qr.get(0))).as("olivia does not run that event")
                .hasStatus(HttpStatus.FORBIDDEN);

        assertThat(scan(organizer("olivia"), eventId, qr.get(0))).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.result", result -> result.assertThat().isEqualTo("ADMITTED"))
                .hasPathSatisfying("$.seatCode", seat -> seat.assertThat().isEqualTo("VIP-A-01"));
        String firstUse = jdbc.sql("select checked_in_at from ticket where qr_token = :qr").param("qr", qr.get(0))
                .query(OffsetDateTime.class).single().toInstant().toString();
        assertThat(scan(organizer("olivia"), eventId, qr.get(0))).as("second scan")
                .hasStatus(HttpStatus.CONFLICT).bodyJson()
                .hasPathSatisfying("$.result", result -> result.assertThat().isEqualTo("ALREADY_USED"))
                .hasPathSatisfying("$.checkedInAt", at -> at.assertThat().asString().startsWith(firstUse.substring(0, 19)));

        assertThat(scan(admin("ada"), eventId, qr.get(1))).as("admins may check in anywhere").hasStatusOk();
        assertThat(mvc.get().uri("/api/tickets").param("bookingId", bookingId.toString()).with(customer("an")))
                .hasStatusOk().bodyJson().extractingPath("$[*].checkedInAt").asArray().doesNotContainNull();
    }

    @Test
    void simultaneousScansAdmitExactlyOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        send(bookingId, UUID.randomUUID(), confirmed(bookingId, eventId, "an", "GA-A-09"));
        await().atMost(Duration.ofSeconds(30)).until(() -> ticketsFor(bookingId) == 1);
        String qr = qrTokens(bookingId).getFirst();
        Caller gate = new Caller("ada", "ada@example.com", true, Set.of(Roles.ADMIN));

        Set<String> outcomes = ConcurrentHashMap.newKeySet();
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService gates = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                gates.submit(() -> {
                    go.await();
                    try {
                        results.add(checkIn.scan(gate, eventId, qr).result().name());
                    } catch (ApiException e) {
                        results.add(String.valueOf(e.properties().get("result")));
                    }
                    return null;
                });
            }
            go.countDown();
        }
        outcomes.addAll(results);
        assertThat(results).hasSize(20).filteredOn("ADMITTED"::equals).hasSize(1);
        assertThat(outcomes).containsExactlyInAnyOrder("ADMITTED", "ALREADY_USED");
    }

    private MvcTestResult scan(RequestPostProcessor who, UUID eventId, String qrToken) {
        return mvc.post().uri("/api/tickets/check-in").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"%s\",\"qrToken\":\"%s\"}".formatted(eventId, qrToken)).exchange();
    }

    private List<String> qrTokens(UUID bookingId) {
        return jdbc.sql("select qr_token from ticket where booking_id = :id order by seat_code").param("id", bookingId)
                .query(String.class).list();
    }

    private void publishEvent(UUID eventId, String organizerId) {
        EventPublished published = new EventPublished(EventPublished.CURRENT_VERSION, eventId, "Rock Night", "Mỹ Đình",
                "Hanoi", Instant.now().plus(30, ChronoUnit.DAYS), Instant.now(),
                List.of(new SectionSpec("VIP", "VIP", 1, 2, 3_000_000)), false, organizerId);
        var record = new ProducerRecord<>(Topics.EVENT_EVENTS, eventId.toString(), jsonMapper.writeValueAsString(published));
        record.headers()
                .add(MessageHeaders.MESSAGE_ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                .add(MessageHeaders.MESSAGE_TYPE, "EventPublished".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    private static BookingConfirmed confirmed(UUID bookingId, String user, String... seats) {
        return confirmed(bookingId, UUID.randomUUID(), user, seats);
    }

    private static BookingConfirmed confirmed(UUID bookingId, UUID eventId, String user, String... seats) {
        return new BookingConfirmed(BookingEvents.CURRENT_VERSION, bookingId, eventId, user, user + "@example.com",
                "Rock Night", "Mỹ Đình", Instant.now().plus(30, ChronoUnit.DAYS),
                List.of(seats).stream().map(seat -> new SeatLine(seat, 800_000)).toList(), 800_000L * seats.length);
    }

    private void send(UUID bookingId, UUID messageId, Object message) {
        var record = new ProducerRecord<>(Topics.BOOKING_EVENTS, bookingId.toString(), jsonMapper.writeValueAsString(message));
        record.headers()
                .add(MessageHeaders.MESSAGE_ID, messageId.toString().getBytes(StandardCharsets.UTF_8))
                .add(MessageHeaders.MESSAGE_TYPE, message.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    private int ticketsFor(UUID bookingId) {
        return jdbc.sql("select count(*) from ticket where booking_id = :id").param("id", bookingId)
                .query(Integer.class).single();
    }
}
