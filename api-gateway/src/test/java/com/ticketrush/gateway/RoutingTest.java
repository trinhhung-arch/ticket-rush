package com.ticketrush.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Stands up one stub HTTP server per service and checks which one each path reaches. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutingTest {

    private static final HttpServer EVENT = stub("event-service");
    private static final HttpServer BOOKING = stub("booking-service");
    private static final HttpServer PAYMENT = stub("payment-service");
    private static final HttpServer TICKET = stub("ticket-service");
    private static final HttpServer WAITING_ROOM = stub("waiting-room-service");

    @Value("${local.server.port}")
    int port;

    @DynamicPropertySource
    static void serviceUris(DynamicPropertyRegistry registry) {
        registry.add("ticketrush.routes.event-service", () -> uri(EVENT));
        registry.add("ticketrush.routes.booking-service", () -> uri(BOOKING));
        registry.add("ticketrush.routes.payment-service", () -> uri(PAYMENT));
        registry.add("ticketrush.routes.ticket-service", () -> uri(TICKET));
        registry.add("ticketrush.routes.waiting-room-service", () -> uri(WAITING_ROOM));
    }

    @AfterAll
    static void stopStubs() {
        for (HttpServer server : new HttpServer[] {EVENT, BOOKING, PAYMENT, TICKET, WAITING_ROOM}) {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "/api/events,                           event-service",
            "/api/events/0b6f6f0e-1d7a-4c55-9d5d-2a0f1c6e7a11, event-service",
            "/api/events/0b6f6f0e-1d7a-4c55-9d5d-2a0f1c6e7a11/seats, booking-service",
            "/api/bookings,                         booking-service",
            "/api/bookings/42,                      booking-service",
            "/api/payments/42,                      payment-service",
            "/api/tickets/42,                       ticket-service",
            "/api/queue/events/42,                  waiting-room-service"
    })
    void routesEachPathToItsService(String path, String expectedService) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(expectedService);
    }

    @ParameterizedTest
    @CsvSource({"/internal/admin", "/actuator/../api-docs"})
    void doesNotExposeUnknownPaths(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    private static HttpServer stub(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = name.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String uri(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }
}
