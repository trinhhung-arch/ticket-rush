package com.ticketrush.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import com.ticketrush.contracts.booking.BookingEvents;
import com.ticketrush.contracts.booking.BookingEvents.BookingCancelled;
import com.ticketrush.contracts.payment.PaymentEvents;
import com.ticketrush.contracts.payment.PaymentEvents.PaymentRefunded;

/** FR-NTF-02 against Mailpit: the reason is spelled out, and a refund is reported whatever order the events come in. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CancellationEmailIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Value("${test.mailpit.api}")
    String mailpit;

    @Test
    void anExpiredHoldGetsOneEmailWithTheReason() throws Exception {
        UUID bookingId = UUID.randomUUID();
        String email = "hoa-" + bookingId + "@example.com";
        BookingCancelled cancelled = cancelled(bookingId, email, "HOLD_EXPIRED");
        UUID messageId = UUID.randomUUID();
        send(Topics.BOOKING_EVENTS, bookingId, messageId, cancelled);
        send(Topics.BOOKING_EVENTS, bookingId, messageId, cancelled);

        await().atMost(Duration.ofSeconds(30)).until(() -> messagesTo(email).size() == 1);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).until(() -> messagesTo(email).size() == 1);
        assertThat(messagesTo(email).getFirst().get("Subject")).isEqualTo("Đơn đặt vé đã bị huỷ");
        assertThat(html(email, "Đơn đặt vé đã bị huỷ")).contains("hết 10 phút giữ chỗ");
    }

    @Test
    void refundEmailIsSentWhenTheRefundArrivesBeforeTheCancellation() throws Exception {
        UUID bookingId = UUID.randomUUID();
        String email = "khanh-" + bookingId + "@example.com";

        send(Topics.PAYMENT_EVENTS, bookingId, UUID.randomUUID(), refunded(bookingId));
        send(Topics.BOOKING_EVENTS, bookingId, UUID.randomUUID(), cancelled(bookingId, email, "SEAT_CONFLICT"));

        await().atMost(Duration.ofSeconds(30)).until(() -> messagesTo(email).size() == 2);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).until(() -> messagesTo(email).size() == 2);
        assertThat(messagesTo(email)).extracting(message -> message.get("Subject"))
                .containsExactlyInAnyOrder("Đơn đặt vé đã bị huỷ", "Đã hoàn tiền cho đơn đặt vé");
        assertThat(html(email, "Đã hoàn tiền cho đơn đặt vé")).contains("1.600.000 VND", "đã được bán cho một đơn khác");
    }

    @Test
    void refundEmailIsSentWhenTheCancellationArrivesFirst() throws Exception {
        UUID bookingId = UUID.randomUUID();
        String email = "long-" + bookingId + "@example.com";

        send(Topics.BOOKING_EVENTS, bookingId, UUID.randomUUID(), cancelled(bookingId, email, "HOLD_EXPIRED"));
        await().atMost(Duration.ofSeconds(30)).until(() -> messagesTo(email).size() == 1);
        send(Topics.PAYMENT_EVENTS, bookingId, UUID.randomUUID(), refunded(bookingId));

        await().atMost(Duration.ofSeconds(30)).until(() -> messagesTo(email).size() == 2);
        assertThat(html(email, "Đã hoàn tiền cho đơn đặt vé")).contains("hết 10 phút giữ chỗ");
    }

    private static BookingCancelled cancelled(UUID bookingId, String email, String reason) {
        return new BookingCancelled(BookingEvents.CURRENT_VERSION, bookingId, UUID.randomUUID(), "an", email, reason);
    }

    private static PaymentRefunded refunded(UUID bookingId) {
        return new PaymentRefunded(PaymentEvents.CURRENT_VERSION, UUID.randomUUID(), bookingId, 1_600_000, "SEAT_CONFLICT");
    }

    private void send(String topic, UUID bookingId, UUID messageId, Object message) {
        var record = new ProducerRecord<>(topic, bookingId.toString(), jsonMapper.writeValueAsString(message));
        record.headers()
                .add(MessageHeaders.MESSAGE_ID, messageId.toString().getBytes(StandardCharsets.UTF_8))
                .add(MessageHeaders.MESSAGE_TYPE, message.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    private String html(String email, String subject) throws Exception {
        Map<String, Object> summary = messagesTo(email).stream()
                .filter(message -> subject.equals(message.get("Subject"))).findFirst().orElseThrow();
        return JsonPath.read(get("/api/v1/message/" + summary.get("ID")), "$.HTML");
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
