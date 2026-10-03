package com.ticketrush.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.TestcontainersConfiguration;
import com.ticketrush.contracts.MessageHeaders;
import com.ticketrush.contracts.Topics;
import com.ticketrush.contracts.ticket.TicketEvents;
import com.ticketrush.contracts.ticket.TicketEvents.IssuedTicket;
import com.ticketrush.contracts.ticket.TicketEvents.TicketsIssued;

/** FR-NTF-01, checked against a real SMTP server (Mailpit) through its HTTP API. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TicketEmailIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Value("${test.mailpit.api}")
    String mailpit;

    @Test
    void sendsOneEmailWithAQrCodePerTicketEvenWhenTheEventIsRedelivered() throws Exception {
        UUID bookingId = UUID.randomUUID();
        String email = "an-" + bookingId + "@example.com";
        TicketsIssued issued = new TicketsIssued(TicketEvents.CURRENT_VERSION, bookingId, UUID.randomUUID(), "an", email,
                "Rock Night <Live>", "Sân vận động Mỹ Đình", Instant.now().plus(30, ChronoUnit.DAYS), List.of(
                new IssuedTicket(UUID.randomUUID(), "VIP-A-01", "token-1"),
                new IssuedTicket(UUID.randomUUID(), "VIP-A-02", "token-2")));

        UUID messageId = UUID.randomUUID();
        send(bookingId, messageId, issued);
        send(bookingId, messageId, issued);
        send(bookingId, UUID.randomUUID(), issued);

        await().atMost(Duration.ofSeconds(30)).until(() -> messagesTo(email).size() == 1);
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).until(() -> messagesTo(email).size() == 1);

        Map<String, Object> summary = messagesTo(email).getFirst();
        assertThat(summary.get("Subject")).isEqualTo("Vé của bạn: Rock Night <Live>");
        String detail = get("/api/v1/message/" + summary.get("ID"));
        assertThat((List<?>) JsonPath.read(detail, "$.Inline")).hasSize(2);
        assertThat((String) JsonPath.read(detail, "$.HTML"))
                .contains("VIP-A-01", "VIP-A-02", "Rock Night &lt;Live&gt;")
                .doesNotContain("<Live>");
    }

    private void send(UUID bookingId, UUID messageId, Object message) {
        var record = new ProducerRecord<>(Topics.TICKET_EVENTS, bookingId.toString(), jsonMapper.writeValueAsString(message));
        record.headers()
                .add(MessageHeaders.MESSAGE_ID, messageId.toString().getBytes(StandardCharsets.UTF_8))
                .add(MessageHeaders.MESSAGE_TYPE, message.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    private List<Map<String, Object>> messagesTo(String email) throws Exception {
        List<Map<String, Object>> messages = JsonPath.read(get("/api/v1/messages"), "$.messages");
        return messages.stream()
                .filter(message -> JsonPath.read(message, "$.To[*].Address").toString().contains(email))
                .toList();
    }

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(mailpit + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
