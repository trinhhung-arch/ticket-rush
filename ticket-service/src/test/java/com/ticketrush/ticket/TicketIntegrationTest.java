package com.ticketrush.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.TestcontainersConfiguration;
import com.ticketrush.common.contract.BookingEvents;
import com.ticketrush.common.contract.BookingEvents.BookingConfirmed;
import com.ticketrush.common.contract.SeatLine;
import com.ticketrush.common.messaging.MessageHeaders;
import com.ticketrush.common.messaging.Topics;

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

        assertThat(mvc.get().uri("/api/tickets").header("X-User-Id", user)).hasStatusOk().bodyJson()
                .extractingPath("$[*].seatCode").asArray().containsExactly("GA-A-01", "GA-A-02");
        assertThat(mvc.get().uri("/api/tickets").param("bookingId", theirs.toString()).header("X-User-Id", user))
                .hasStatusOk().bodyJson().extractingPath("$").asArray().isEmpty();
    }

    private static BookingConfirmed confirmed(UUID bookingId, String user, String... seats) {
        return new BookingConfirmed(BookingEvents.CURRENT_VERSION, bookingId, UUID.randomUUID(), user, user + "@example.com",
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
