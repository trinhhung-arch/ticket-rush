package com.ticketrush.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ticketrush.testing.RedisContainerConfiguration;

/** FR-GW-02 and NFR-SEC-06 against a real Redis: 10 booking requests per second per signed-in user. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = Tokens.SECRET_PROPERTY)
@Import(RedisContainerConfiguration.class)
class RateLimitTest {

    private static final HttpServer BOOKING = stubReturning201();

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String uri = "http://localhost:" + BOOKING.getAddress().getPort();
        for (String service : List.of("event-service", "booking-service", "payment-service", "ticket-service",
                "waiting-room-service")) {
            registry.add("ticketrush.routes." + service, () -> uri);
        }
    }

    @AfterAll
    static void stopStub() {
        BOOKING.stop(0);
    }

    @Test
    void aBurstFromOneUserIsCutAtTenPerSecondWhileOthersAreUnaffected() {
        String user = "user-" + UUID.randomUUID();

        Map<Integer, Long> statuses = burst(20, user);

        assertThat(statuses.getOrDefault(201, 0L)).as("allowed").isBetween(10L, 12L);
        assertThat(statuses.getOrDefault(429, 0L)).as("rejected").isGreaterThanOrEqualTo(8L);
        assertThat(burst(1, "someone-else")).containsEntry(201, 1L);
    }

    @Test
    void anonymousCallersAreTurnedAwayBeforeTheLimiter() {
        assertThat(burst(5, null)).containsOnlyKeys(401);
    }

    @Test
    void readingBookingsIsNotRateLimited() throws Exception {
        for (int i = 0; i < 15; i++) {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/bookings"))
                    .header("Authorization", Tokens.bearer("reader")).build(), HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(201);
        }
    }

    /** RES-08: opening queue streams is limited too, as each one holds a thread in the waiting room. */
    @Test
    void openingQueueStreamsIsCutAfterABurstOfThree() {
        String user = "user-" + UUID.randomUUID();
        URI stream = URI.create("http://localhost:" + port + "/api/queue/events/" + UUID.randomUUID() + "/stream");

        Map<Integer, Long> statuses = IntStream.range(0, 10)
                .mapToObj(i -> http.sendAsync(HttpRequest.newBuilder(stream).header("Authorization", Tokens.bearer(user))
                        .build(), HttpResponse.BodyHandlers.discarding()).thenApply(HttpResponse::statusCode))
                .toList().stream().map(CompletableFuture::join)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        assertThat(statuses.getOrDefault(201, 0L)).as("allowed").isBetween(3L, 4L);
        assertThat(statuses.getOrDefault(429, 0L)).as("rejected").isGreaterThanOrEqualTo(6L);
    }

    /** Fires {@code count} POST /api/bookings at once and counts the status codes. */
    private Map<Integer, Long> burst(int count, String userId) {
        List<CompletableFuture<Integer>> responses = IntStream.range(0, count).mapToObj(i -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/bookings"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("Content-Type", "application/json");
            if (userId != null) {
                request.header("Authorization", Tokens.bearer(userId));
            }
            return http.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding()).thenApply(HttpResponse::statusCode);
        }).toList();
        return responses.stream().map(CompletableFuture::join)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static HttpServer stubReturning201() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(201, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
