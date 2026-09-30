package com.ticketrush.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
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
