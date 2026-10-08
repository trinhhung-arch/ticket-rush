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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = Tokens.SECRET_PROPERTY)
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
        HttpResponse<String> response = get(path, Tokens.bearer("an"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(expectedService);
    }

    /** NFR-SEC-01: the gateway turns away calls without a valid token before any service sees them. */
    @ParameterizedTest
    @CsvSource({"/api/bookings", "/api/payments/42", "/api/tickets", "/api/queue/events/42/status"})
    void protectedPathsNeedAValidToken(String path) throws Exception {
        HttpResponse<String> anonymous = get(path, null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(anonymous.headers().firstValue("WWW-Authenticate")).get().asString().startsWith("Bearer");

        assertThat(get(path, Tokens.bearer("mallory", "test-untrusted-secret-0123456789abcdef")).statusCode())
                .as("token signed with the wrong key").isEqualTo(401);
    }

    @ParameterizedTest
    @CsvSource({
            "/api/events,                           event-service",
            "/api/events/0b6f6f0e-1d7a-4c55-9d5d-2a0f1c6e7a11/seats, booking-service",
            "/api-docs/payment-service,             payment-service",
            "/api-docs/waiting-room-service,        waiting-room-service"
    })
    void browsingAndApiDocsNeedNoToken(String path, String expectedService) throws Exception {
        HttpResponse<String> response = get(path, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(expectedService);
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest
    @CsvSource({"/internal/admin", "/actuator/../api-docs"})
    void doesNotExposeUnknownPaths(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());

        // Spring Security's firewall answers path traversal with 400 before routing is even tried.
        assertThat(response.statusCode()).isIn(400, 404);
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
