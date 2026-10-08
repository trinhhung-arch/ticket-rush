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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/** FR-GW-02 and NFR-SEC-06 against a real Redis: 10 booking requests per second per caller. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RateLimitTest.RedisContainer.class)
class RateLimitTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisContainer {

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);
        }
    }

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
    void anonymousCallersShareABucketPerIp() {
        Map<Integer, Long> statuses = burst(20, null);

        assertThat(statuses.getOrDefault(429, 0L)).isGreaterThanOrEqualTo(8L);
    }

    @Test
    void readingBookingsIsNotRateLimited() throws Exception {
        for (int i = 0; i < 15; i++) {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/bookings"))
                    .header("X-User-Id", "reader").build(), HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(201);
        }
    }

    /** Fires {@code count} POST /api/bookings at once and counts the status codes. */
    private Map<Integer, Long> burst(int count, String userId) {
        List<CompletableFuture<Integer>> responses = IntStream.range(0, count).mapToObj(i -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/bookings"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("Content-Type", "application/json");
            if (userId != null) {
                request.header("X-User-Id", userId);
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
