package com.ticketrush.booking;

import static com.ticketrush.security.TestJwts.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import com.ticketrush.booking.domain.BookingStatus;
import com.ticketrush.booking.saga.BookingSaga;
import com.ticketrush.contracts.Topics;
import com.ticketrush.contracts.payment.PaymentEvents;
import com.ticketrush.contracts.payment.PaymentEvents.PaymentCreated;
import com.ticketrush.contracts.payment.PaymentEvents.PaymentFailed;
import com.ticketrush.contracts.payment.PaymentEvents.PaymentSucceeded;
import com.ticketrush.web.ApiException;

/** The booking saga, driven by the payment events payment-service would send (FR-BKG-05, FR-BKG-09, FR-PAY-06). */
class BookingSagaIntegrationTest extends BookingTestSupport {

    @Autowired
    BookingSaga saga;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void paidBookingIsConfirmedAndItsSeatsSold() {
        UUID eventId = publishEventOnSale();
        UUID bookingId = bookingService.create(booking("an", eventId, "VIP-A-01", "VIP-A-02")).booking().id();

        assertThat(outboxTypesFor(bookingId)).containsExactly("CreatePayment");
        String createPayment = outboxPayload(bookingId, "CreatePayment");
        assertThat((Integer) JsonPath.read(createPayment, "$.amountVnd")).isEqualTo(6_000_000);
        assertThat((String) JsonPath.read(createPayment, "$.userId")).isEqualTo("an");

        UUID paymentId = UUID.randomUUID();
        paymentCreated(bookingId, paymentId);
        await().atMost(WAIT).until(() -> statusOf(bookingId) == BookingStatus.AWAITING_PAYMENT);
        assertThat(bookingService.get(bookingId, "an").checkoutUrl()).endsWith("/api/payments/" + paymentId + "/checkout");

        paymentSucceeded(bookingId, paymentId);
        await().atMost(WAIT).until(() -> statusOf(bookingId) == BookingStatus.CONFIRMED);

        assertThat(soldTo(eventId, "VIP-A-01")).isEqualTo(bookingId);
        assertThat(soldTo(eventId, "VIP-A-02")).isEqualTo(bookingId);
        assertThat(outboxTypesFor(bookingId)).containsExactly("CreatePayment", "BookingConfirmed");
        String confirmed = outboxPayload(bookingId, "BookingConfirmed");
        assertThat((List<?>) JsonPath.read(confirmed, "$.seats")).hasSize(2);
        assertThat((String) JsonPath.read(confirmed, "$.eventName")).isEqualTo("Rock Night");

        assertThat(mvc.get().uri("/api/events/{id}/seats", eventId)).hasStatusOk().bodyJson()
                .extractingPath("$.sold").isEqualTo(2);
        assertThatThrownBy(() -> bookingService.create(booking("binh", eventId, "VIP-A-01")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * NFR-SEC-01, FR-PAY-06: a PaymentSucceeded event that payment-service does not back (a forged event
     * on the bus) must not confirm the booking or issue a ticket. Here the booking is never marked paid.
     */
    @Test
    void forgedPaymentSucceededIsRejected() {
        UUID eventId = publishEventOnSale();
        UUID bookingId = bookingService.create(booking("an", eventId, "VIP-A-01")).booking().id();
        UUID paymentId = UUID.randomUUID();
        paymentCreated(bookingId, paymentId);
        await().atMost(WAIT).until(() -> statusOf(bookingId) == BookingStatus.AWAITING_PAYMENT);

        // A forged success: never marked paid with payment-service, so verification fails.
        send(Topics.PAYMENT_EVENTS, bookingId, UUID.randomUUID(),
                new PaymentSucceeded(PaymentEvents.CURRENT_VERSION, UUID.randomUUID(), bookingId, 0, Instant.now()));

        // Give the listener time to process and (correctly) decline to confirm.
        await().during(Duration.ofSeconds(2)).atMost(WAIT)
                .until(() -> statusOf(bookingId) == BookingStatus.AWAITING_PAYMENT);
        assertThat(soldTo(eventId, "VIP-A-01")).isNull();
        assertThat(outboxTypesFor(bookingId)).containsExactly("CreatePayment");
    }

    @Test
    void declinedPaymentCancelsTheBookingAndFreesItsSeats() {
        UUID eventId = publishEventOnSale();
        UUID bookingId = bookingService.create(booking("chi", eventId, "GA-A-01")).booking().id();
        UUID paymentId = UUID.randomUUID();
        paymentCreated(bookingId, paymentId);

        send(Topics.PAYMENT_EVENTS, bookingId, UUID.randomUUID(),
                new PaymentFailed(PaymentEvents.CURRENT_VERSION, paymentId, bookingId, "DECLINED"));

        await().atMost(WAIT).until(() -> statusOf(bookingId) == BookingStatus.CANCELLED);
        assertThat(cancelReasonOf(bookingId)).isEqualTo("PAYMENT_FAILED");
        assertThat(outboxTypesFor(bookingId)).containsExactly("CreatePayment", "BookingCancelled");
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(bookingService.create(booking("dung", eventId, "GA-A-01")).created()).isTrue());
    }

    /** FR-BKG-05: the sweep cancels the booking, stops its payment and frees the seat. */
    @Test
    void expiredHoldIsCancelledAndItsPaymentStopped() {
        UUID eventId = publishEventOnSale();
        UUID bookingId = bookingService.create(booking("em", eventId, "GA-A-02")).booking().id();

        makeOverdue(bookingId);
        saga.expireOverdue(100);

        await().atMost(WAIT).until(() -> statusOf(bookingId) == BookingStatus.CANCELLED);
        assertThat(cancelReasonOf(bookingId)).isEqualTo("HOLD_EXPIRED");
        assertThat(outboxTypesFor(bookingId)).containsExactly("CreatePayment", "BookingCancelled", "CancelPayment");
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(bookingService.create(booking("giang", eventId, "GA-A-02")).created()).isTrue());
    }

    /** FR-PAY-06: money that arrives after the booking expired is sent back, and no seat is sold. */
    @Test
    void paymentArrivingAfterExpiryIsRefunded() {
        UUID eventId = publishEventOnSale();
        UUID bookingId = bookingService.create(booking("hai", eventId, "GA-A-03")).booking().id();
        UUID paymentId = UUID.randomUUID();
        paymentCreated(bookingId, paymentId);
        await().atMost(WAIT).until(() -> statusOf(bookingId) == BookingStatus.AWAITING_PAYMENT);
        makeOverdue(bookingId);
        saga.expireOverdue(100);
        await().atMost(WAIT).until(() -> statusOf(bookingId) == BookingStatus.CANCELLED);

        paymentSucceeded(bookingId, paymentId);

        await().atMost(WAIT).until(() -> outboxTypesFor(bookingId).contains("RefundPayment"));
        assertThat(statusOf(bookingId)).isEqualTo(BookingStatus.CANCELLED);
        assertThat((String) JsonPath.read(outboxPayload(bookingId, "RefundPayment"), "$.reason"))
                .isEqualTo("BOOKING_HOLD_EXPIRED");
        assertThat(soldTo(eventId, "GA-A-03")).isNull();
    }

    /**
     * NFR-AVAIL-05: Redis loses every hold mid-sale, so two customers hold and pay for the same seat.
     * The conditional update in Postgres sells it once; the second payer is refunded.
     */
    @Test
    void databaseGuardRefundsTheSecondPayerWhenRedisLosesItsHolds() {
        UUID eventId = publishEventOnSale();
        UUID first = bookingService.create(booking("khanh", eventId, "GA-B-05")).booking().id();
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
        UUID second = bookingService.create(booking("linh", eventId, "GA-B-05")).booking().id();

        UUID firstPayment = UUID.randomUUID();
        UUID secondPayment = UUID.randomUUID();
        paymentCreated(first, firstPayment);
        paymentCreated(second, secondPayment);
        paymentSucceeded(first, firstPayment);
        paymentSucceeded(second, secondPayment);

        await().atMost(WAIT).until(() -> statusOf(first) == BookingStatus.CONFIRMED
                && statusOf(second) == BookingStatus.CANCELLED);
        assertThat(cancelReasonOf(second)).isEqualTo("SEAT_CONFLICT");
        assertThat(outboxTypesFor(second)).contains("BookingCancelled", "RefundPayment");
        assertThat(soldTo(eventId, "GA-B-05")).isEqualTo(first);
    }

    /** NFR-CORR-03: the same PaymentSucceeded delivered three times confirms the booking once. */
    @Test
    void redeliveredPaymentEventsAreAppliedOnce() {
        UUID eventId = publishEventOnSale();
        UUID bookingId = bookingService.create(booking("minh", eventId, "VIP-B-03")).booking().id();
        UUID paymentId = UUID.randomUUID();
        paymentCreated(bookingId, paymentId);

        paymentVerifier.markPaid(bookingId, paymentId);
        UUID messageId = UUID.randomUUID();
        var succeeded = new PaymentSucceeded(PaymentEvents.CURRENT_VERSION, paymentId, bookingId, 3_000_000, Instant.now());
        send(Topics.PAYMENT_EVENTS, bookingId, messageId, succeeded);
        send(Topics.PAYMENT_EVENTS, bookingId, messageId, succeeded);
        UUID lastMessageId = UUID.randomUUID();
        send(Topics.PAYMENT_EVENTS, bookingId, lastMessageId, succeeded);

        // Messages of one booking share a partition, so once the last one is processed so are the others.
        await().atMost(WAIT).until(() -> jdbc.sql("select count(*) from processed_message where message_id = :id")
                .param("id", lastMessageId).query(Integer.class).single() == 1);
        assertThat(statusOf(bookingId)).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(outboxTypesFor(bookingId)).containsExactly("CreatePayment", "BookingConfirmed");
    }

    /** FR-BKG-06: cancelling frees the seat at once and stops the payment; a paid booking stays paid. */
    @Test
    void customerCanCancelAnUnpaidBookingButNotAPaidOne() {
        UUID eventId = publishEventOnSale();
        UUID unpaid = bookingService.create(booking("oanh", eventId, "VIP-A-03")).booking().id();

        assertThat(mvc.post().uri("/api/bookings/{id}/cancel", unpaid).with(customer("oanh"))).hasStatusOk()
                .bodyJson().hasPathSatisfying("$.cancelReason", reason -> reason.assertThat().isEqualTo("USER_CANCELLED"));
        assertThat(outboxTypesFor(unpaid)).containsExactly("CreatePayment", "BookingCancelled", "CancelPayment");
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(bookingService.create(booking("phuong", eventId, "VIP-A-03")).created()).isTrue());
        assertThat(mvc.post().uri("/api/bookings/{id}/cancel", unpaid).with(customer("oanh")))
                .as("cancelling twice").hasStatusOk();
        assertThat(mvc.post().uri("/api/bookings/{id}/cancel", unpaid).with(customer("someone-else")))
                .hasStatus(HttpStatus.NOT_FOUND);

        UUID paid = bookingService.create(booking("quang", eventId, "VIP-B-01")).booking().id();
        UUID paymentId = UUID.randomUUID();
        paymentCreated(paid, paymentId);
        paymentSucceeded(paid, paymentId);
        await().atMost(WAIT).until(() -> statusOf(paid) == BookingStatus.CONFIRMED);
        assertThat(mvc.post().uri("/api/bookings/{id}/cancel", paid).with(customer("quang")))
                .hasStatus(HttpStatus.CONFLICT);
    }

    /**
     * BIZ-02: seats a customer let go unpaid (cancelled, expired or declined) stay out of that customer's
     * reach for a while, so one account cannot keep the same seats for the whole sale; anyone else may take them.
     */
    @Test
    void seatsLetGoUnpaidAreNotHeldAgainBySameCustomerAtOnce() {
        UUID eventId = publishEventOnSale();
        UUID cancelled = bookingService.create(booking("hoa", eventId, "GA-B-01", "GA-B-02")).booking().id();
        assertThat(mvc.post().uri("/api/bookings/{id}/cancel", cancelled).with(customer("hoa"))).hasStatusOk();
        UUID expired = bookingService.create(booking("hoa", eventId, "GA-B-03")).booking().id();
        makeOverdue(expired);
        saga.expireOverdue(100);
        UUID declined = bookingService.create(booking("hoa", eventId, "GA-B-04")).booking().id();
        UUID paymentId = UUID.randomUUID();
        paymentCreated(declined, paymentId);
        send(Topics.PAYMENT_EVENTS, declined, UUID.randomUUID(),
                new PaymentFailed(PaymentEvents.CURRENT_VERSION, paymentId, declined, "DECLINED"));
        await().atMost(WAIT).until(() -> statusOf(expired) == BookingStatus.CANCELLED
                && statusOf(declined) == BookingStatus.CANCELLED);

        for (String seat : List.of("GA-B-02", "GA-B-03", "GA-B-04")) {
            assertThatThrownBy(() -> bookingService.create(booking("hoa", eventId, seat))).as(seat)
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.properties()).containsEntry("seatCode", seat).containsKey("availableToYouAt");
                    });
        }
        assertThat(bookingService.create(booking("hoa", eventId, "GA-B-05")).created()).as("other seats").isTrue();
        await().atMost(WAIT).untilAsserted(() -> assertThat(
                bookingService.create(booking("khoa", eventId, "GA-B-02", "GA-B-03", "GA-B-04")).created())
                .as("other customers").isTrue());
    }

    @Test
    void customersSeeTheirOwnBookingsNewestFirst() {
        UUID eventId = publishEventOnSale();
        String user = "nga-" + UUID.randomUUID();
        UUID older = bookingService.create(booking(user, eventId, "GA-B-01")).booking().id();
        UUID newer = bookingService.create(booking(user, eventId, "GA-B-02")).booking().id();
        bookingService.create(booking("someone-else", eventId, "GA-B-03"));

        assertThat(mvc.get().uri("/api/bookings").with(customer(user))).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.totalItems", total -> total.assertThat().isEqualTo(2))
                .hasPathSatisfying("$.items[*].id",
                        ids -> ids.assertThat().asArray().containsExactly(newer.toString(), older.toString()));
    }

    private void paymentCreated(UUID bookingId, UUID paymentId) {
        send(Topics.PAYMENT_EVENTS, bookingId, UUID.randomUUID(), new PaymentCreated(PaymentEvents.CURRENT_VERSION,
                paymentId, bookingId, "http://localhost:8080/api/payments/" + paymentId + "/checkout",
                Instant.now().plus(10, ChronoUnit.MINUTES)));
    }

    /** A genuine success: payment-service's record is SUCCEEDED, so the saga's verification passes. */
    private void paymentSucceeded(UUID bookingId, UUID paymentId) {
        paymentVerifier.markPaid(bookingId, paymentId);
        send(Topics.PAYMENT_EVENTS, bookingId, UUID.randomUUID(),
                new PaymentSucceeded(PaymentEvents.CURRENT_VERSION, paymentId, bookingId, 800_000, Instant.now()));
    }

    private void makeOverdue(UUID bookingId) {
        jdbc.sql("update booking set expires_at = now() - interval '1 second' where id = :id")
                .param("id", bookingId).update();
    }

    private UUID soldTo(UUID eventId, String seatCode) {
        return jdbc.sql("select booking_id from seat_inventory where event_id = :eventId and seat_code = :seatCode and status = 'SOLD'")
                .param("eventId", eventId).param("seatCode", seatCode)
                .query(UUID.class).optional().orElse(null);
    }
}
