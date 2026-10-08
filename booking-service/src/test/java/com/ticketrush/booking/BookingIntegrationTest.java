package com.ticketrush.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.ticketrush.common.web.ApiException;

class BookingIntegrationTest extends BookingTestSupport {

    /** NFR-TEST-03 / FR-BKG-03. */
    @Test
    void oneThousandCustomersRaceForOneSeatAndExactlyOneWins() {
        UUID eventId = publishEventOnSale();
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
        UUID eventId = publishEventOnSale();
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
        UUID eventId = publishEventOnSale();
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
        UUID eventId = publishEventOnSale();
        bookingService.create(booking("dave", eventId, "VIP-B-01", "VIP-B-02"));

        assertThat(mvc.get().uri("/api/events/{id}/seats", eventId)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.total", total -> total.assertThat().isEqualTo(16))
                .hasPathSatisfying("$.held", held -> held.assertThat().isEqualTo(2))
                .hasPathSatisfying("$.available", available -> available.assertThat().isEqualTo(14))
                .hasPathSatisfying("$.seats[?(@.code == 'VIP-B-01')].state",
                        state -> state.assertThat().asArray().containsExactly("HELD"));
    }

    /** FR-BKG-07: six tickets per customer and event; cancelled bookings free up the allowance. */
    @Test
    void customersCanHoldAtMostSixTicketsPerEvent() {
        UUID eventId = publishEventOnSale();
        String user = "rin-" + UUID.randomUUID();
        bookingService.create(booking(user, eventId, "GA-A-01", "GA-A-02", "GA-A-03", "GA-A-04"));

        assertThatThrownBy(() -> bookingService.create(booking(user, eventId, "GA-B-01", "GA-B-02", "GA-B-03")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.properties()).containsEntry("current", 4L);
                });
        UUID two = bookingService.create(booking(user, eventId, "GA-B-01", "GA-B-02")).booking().id();
        assertThatThrownBy(() -> bookingService.create(booking(user, eventId, "GA-B-03")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
        assertThat(bookingService.create(booking("someone-else", eventId, "GA-B-03")).created())
                .as("the rejected request released its hold").isTrue();

        mvc.post().uri("/api/bookings/{id}/cancel", two).header("X-User-Id", user).exchange();
        assertThat(bookingService.create(booking(user, eventId, "GA-B-04", "GA-B-05")).created()).isTrue();
    }

    /** The limit holds even when one customer fires requests in parallel. */
    @Test
    void parallelRequestsFromOneCustomerCannotExceedTheLimit() {
        UUID eventId = publishEventOnSale();
        String user = "sang-" + UUID.randomUUID();
        List<List<String>> pairs = List.of(List.of("VIP-A-01", "VIP-A-02"), List.of("VIP-A-03", "VIP-B-01"),
                List.of("VIP-B-02", "VIP-B-03"), List.of("GA-A-01", "GA-A-02"), List.of("GA-A-03", "GA-A-04"));
        CountDownLatch startingGun = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (List<String> pair : pairs) {
                pool.submit(() -> {
                    startingGun.await();
                    try {
                        bookingService.create(booking(user, eventId, pair.toArray(String[]::new)));
                        won.incrementAndGet();
                    } catch (ApiException e) {
                        if (e.status() == HttpStatus.UNPROCESSABLE_CONTENT) {
                            limited.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            startingGun.countDown();
        }

        assertThat(won).hasValue(3);
        assertThat(limited).hasValue(2);
    }

    /** FR-WR-03: for a waiting-room event, only a valid token for this buyer and this event gets in. */
    @Test
    void waitingRoomEventsRequireAnAdmissionTokenForThatBuyerAndEvent() throws Exception {
        UUID eventId = publishEvent(Instant.now().minus(1, ChronoUnit.HOURS), true);
        Instant later = Instant.now().plus(10, ChronoUnit.MINUTES);

        assertForbidden(() -> bookingService.create(booking("tam", eventId, "GA-A-01")));
        assertForbidden(() -> bookingService.create(withToken(booking("tam", eventId, "GA-A-01"),
                admissionToken(ADMISSION_KEY, "someone-else", eventId, later))));
        assertForbidden(() -> bookingService.create(withToken(booking("tam", eventId, "GA-A-01"),
                admissionToken(ADMISSION_KEY, "tam", UUID.randomUUID(), later))));
        assertForbidden(() -> bookingService.create(withToken(booking("tam", eventId, "GA-A-01"),
                admissionToken(ADMISSION_KEY, "tam", eventId, Instant.now().minus(1, ChronoUnit.MINUTES)))));
        assertForbidden(() -> bookingService.create(withToken(booking("tam", eventId, "GA-A-01"),
                admissionToken("forged-key-0123456789-0123456789-01234", "tam", eventId, later))));

        assertThat(bookingService.create(withToken(booking("tam", eventId, "GA-A-01"),
                admissionToken(ADMISSION_KEY, "tam", eventId, later))).created()).isTrue();
        assertThat(bookingService.create(booking("tam", publishEventOnSale(), "GA-A-01")).created())
                .as("events without a waiting room need no token").isTrue();
    }

    private static void assertForbidden(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static CreateBooking withToken(CreateBooking booking, String token) {
        return new CreateBooking(booking.userId(), booking.idempotencyKey(), booking.eventId(), booking.seatCodes(),
                booking.email(), token);
    }

    /** Signs a token the way the waiting room does. */
    private static String admissionToken(String key, String userId, UUID eventId, Instant expiresAt) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .issuer("ticketrush-waiting-room").subject(userId).claim("evt", eventId.toString())
                .expirationTime(Date.from(expiresAt)).build());
        jwt.sign(new MACSigner(key.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    @Test
    void rejectsUnknownSeatsAndEventsNotOnSale() {
        UUID onSale = publishEventOnSale();
        assertThatThrownBy(() -> bookingService.create(booking("erin", onSale, "VIP-Z-99")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));

        UUID notYetOnSale = publishEvent(Instant.now().plus(1, ChronoUnit.DAYS));
        assertThatThrownBy(() -> bookingService.create(booking("erin", notYetOnSale, "VIP-A-01")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }



    private MvcTestResult postBooking(String user, String idempotencyKey, String body) {
        return mvc.post().uri("/api/bookings").header("X-User-Id", user).header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }


    private static String content(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
