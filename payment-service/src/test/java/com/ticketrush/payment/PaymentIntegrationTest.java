package com.ticketrush.payment;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.TestcontainersConfiguration;
import com.ticketrush.common.contract.PaymentCommands;
import com.ticketrush.common.contract.PaymentCommands.CancelPayment;
import com.ticketrush.common.contract.PaymentCommands.CreatePayment;
import com.ticketrush.common.contract.PaymentCommands.RefundPayment;
import com.ticketrush.common.messaging.MessageHeaders;
import com.ticketrush.common.messaging.Topics;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JdbcClient jdbc;

    /** FR-PAY-01: the command is processed once even when it is sent again under a new message id. */
    @Test
    void createsOnePaymentPerBooking() {
        UUID bookingId = UUID.randomUUID();
        CreatePayment command = createPayment(bookingId, Instant.now().plus(10, ChronoUnit.MINUTES));
        send(bookingId, command);
        send(bookingId, command);

        UUID paymentId = awaitPayment(bookingId);
        assertThat(paymentsFor(bookingId)).isEqualTo(1);
        assertThat(outboxTypesFor(bookingId)).containsExactly("PaymentCreated");
        assertThat((String) JsonPath.read(outboxPayload(bookingId, "PaymentCreated"), "$.checkoutUrl"))
                .isEqualTo("http://localhost:8080/api/payments/" + paymentId + "/checkout");

        assertThat(mvc.get().uri("/api/payments/{id}", paymentId).header("X-User-Id", "an")).hasStatusOk()
                .bodyJson().extractingPath("$.amountVnd").isEqualTo(1_600_000);
        assertThat(mvc.get().uri("/api/payments/{id}", paymentId).header("X-User-Id", "someone-else"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    /** FR-PAY-02 and FR-PAY-03: the same webhook five times changes the payment once and emits one event. */
    @Test
    void repeatedWebhooksAreAppliedOnce() {
        UUID bookingId = UUID.randomUUID();
        send(bookingId, createPayment(bookingId, Instant.now().plus(10, ChronoUnit.MINUTES)));
        UUID paymentId = awaitPayment(bookingId);

        String webhook = """
                {"paymentId":"%s","transactionId":"txn-%s","outcome":"SUCCEEDED"}
                """.formatted(paymentId, bookingId);
        for (int i = 0; i < 5; i++) {
            assertThat(mvc.post().uri("/api/payments/webhooks/mock-gateway")
                    .contentType(MediaType.APPLICATION_JSON).content(webhook))
                    .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("SUCCEEDED");
        }

        assertThat(outboxTypesFor(bookingId)).containsExactly("PaymentCreated", "PaymentSucceeded");
    }

    @Test
    void declinedCheckoutFailsThePayment() {
        UUID bookingId = UUID.randomUUID();
        send(bookingId, createPayment(bookingId, Instant.now().plus(10, ChronoUnit.MINUTES)));
        UUID paymentId = awaitPayment(bookingId);

        assertThat(checkout(paymentId, "DECLINED")).hasStatusOk()
                .bodyJson().extractingPath("$.payment.status").isEqualTo("FAILED");
        assertThat(checkout(paymentId, "SUCCEEDED")).as("a finished payment cannot be paid again")
                .hasStatus(HttpStatus.CONFLICT);
        assertThat((String) JsonPath.read(outboxPayload(bookingId, "PaymentFailed"), "$.reason")).isEqualTo("DECLINED");
    }

    /** FR-PAY-05: paying after the hold ended is refused and the customer is not charged. */
    @Test
    void paymentAfterTheHoldEndedIsRefused() {
        UUID bookingId = UUID.randomUUID();
        send(bookingId, createPayment(bookingId, Instant.now().minus(1, ChronoUnit.SECONDS)));
        UUID paymentId = awaitPayment(bookingId);

        assertThat(checkout(paymentId, "SUCCEEDED")).hasStatusOk()
                .bodyJson().extractingPath("$.payment.status").isEqualTo("EXPIRED");
        assertThat(outboxTypesFor(bookingId)).containsExactly("PaymentCreated", "PaymentFailed");
        assertThat((String) JsonPath.read(outboxPayload(bookingId, "PaymentFailed"), "$.reason")).isEqualTo("EXPIRED");
    }

    @Test
    void cancelledPaymentCanNoLongerSucceed() {
        UUID bookingId = UUID.randomUUID();
        send(bookingId, createPayment(bookingId, Instant.now().plus(10, ChronoUnit.MINUTES)));
        UUID paymentId = awaitPayment(bookingId);

        send(bookingId, new CancelPayment(PaymentCommands.CURRENT_VERSION, bookingId, "HOLD_EXPIRED"));
        await().atMost(WAIT).until(() -> "CANCELLED".equals(statusOf(paymentId)));

        assertThat(checkout(paymentId, "SUCCEEDED")).hasStatus(HttpStatus.CONFLICT);
        assertThat(outboxTypesFor(bookingId)).containsExactly("PaymentCreated");
    }

    /** FR-PAY-06: a refund request is honoured once, and only for money actually taken. */
    @Test
    void refundsASucceededPaymentOnce() {
        UUID bookingId = UUID.randomUUID();
        send(bookingId, createPayment(bookingId, Instant.now().plus(10, ChronoUnit.MINUTES)));
        UUID paymentId = awaitPayment(bookingId);
        checkout(paymentId, "SUCCEEDED");

        RefundPayment refund = new RefundPayment(PaymentCommands.CURRENT_VERSION, bookingId, "BOOKING_HOLD_EXPIRED");
        send(bookingId, refund);
        send(bookingId, refund);

        await().atMost(WAIT).until(() -> "REFUNDED".equals(statusOf(paymentId)));
        await().during(Duration.ofSeconds(2)).atMost(WAIT).until(() ->
                outboxTypesFor(bookingId).equals(List.of("PaymentCreated", "PaymentSucceeded", "PaymentRefunded")));
        assertThat((Integer) JsonPath.read(outboxPayload(bookingId, "PaymentRefunded"), "$.amountVnd")).isEqualTo(1_600_000);
    }

    private static CreatePayment createPayment(UUID bookingId, Instant expiresAt) {
        return new CreatePayment(PaymentCommands.CURRENT_VERSION, bookingId, "an", 1_600_000, expiresAt);
    }

    private MvcTestResult checkout(UUID paymentId, String outcome) {
        return mvc.post().uri("/api/payments/{id}/checkout", paymentId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"%s\"}".formatted(outcome)).exchange();
    }

    private void send(UUID bookingId, Object command) {
        var record = new ProducerRecord<>(Topics.PAYMENT_COMMANDS, bookingId.toString(), jsonMapper.writeValueAsString(command));
        record.headers()
                .add(MessageHeaders.MESSAGE_ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                .add(MessageHeaders.MESSAGE_TYPE, command.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    private UUID awaitPayment(UUID bookingId) {
        await().atMost(WAIT).until(() -> paymentsFor(bookingId) == 1);
        return jdbc.sql("select id from payment where booking_id = :id").param("id", bookingId).query(UUID.class).single();
    }

    private int paymentsFor(UUID bookingId) {
        return jdbc.sql("select count(*) from payment where booking_id = :id").param("id", bookingId)
                .query(Integer.class).single();
    }

    private String statusOf(UUID paymentId) {
        return jdbc.sql("select status from payment where id = :id").param("id", paymentId).query(String.class).single();
    }

    private List<String> outboxTypesFor(UUID bookingId) {
        return jdbc.sql("select message_type from outbox_message where message_key = :key order by seq")
                .param("key", bookingId.toString()).query(String.class).list();
    }

    private String outboxPayload(UUID bookingId, String type) {
        return jdbc.sql("select payload from outbox_message where message_key = :key and message_type = :type")
                .param("key", bookingId.toString()).param("type", type).query(String.class).single();
    }
}
