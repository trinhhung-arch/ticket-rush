package com.ticketrush.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * NFR-AVAIL-02 with stub services: a slow one is cut off at the 2 s time limit, and one that keeps
 * failing is taken out of rotation after 20 calls, so later callers get an immediate 503.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        Tokens.SECRET_PROPERTY, "ticketrush.resilience.open-for=60s"})
class ResilienceTest {

    private static final AtomicInteger FAILING_CALLS = new AtomicInteger();
    private static final HttpServer SLOW = stub(exchange -> {
        sleep(Duration.ofSeconds(4));
        exchange.sendResponseHeaders(200, -1);
    });
    private static final HttpServer FAILING = stub(exchange -> {
        FAILING_CALLS.incrementAndGet();
        exchange.sendResponseHeaders(500, -1);
    });
    private static final HttpServer HEALTHY = stub(exchange -> exchange.sendResponseHeaders(200, -1));
    private static final HttpServer BUSY = stub(exchange -> {
        sleep(Duration.ofMillis(300));
        exchange.sendResponseHeaders(200, -1);
    });

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("ticketrush.routes.ticket-service", () -> uri(SLOW));
        registry.add("ticketrush.routes.payment-service", () -> uri(FAILING));
        registry.add("ticketrush.routes.waiting-room-service", () -> uri(BUSY));
        for (String service : List.of("event-service", "booking-service")) {
            registry.add("ticketrush.routes." + service, () -> uri(HEALTHY));
        }
    }

    @AfterAll
    static void stopStubs() {
        for (HttpServer server : new HttpServer[] {SLOW, FAILING, HEALTHY, BUSY}) {
            server.stop(0);
        }
    }

    @Test
    void aSlowServiceIsCutOffAfterTwoSeconds() throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> response = get("/api/tickets");
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("\"service\":\"ticket-service\"");
        assertThat(took).isBetween(Duration.ofMillis(1_900), Duration.ofMillis(3_500));
    }

    @Test
    void aFailingServiceIsTakenOutOfRotationWithoutAffectingOthers() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(get("/api/payments/42").statusCode()).isEqualTo(503);
        }
        assertThat(FAILING_CALLS).as("every call reached the service while the breaker was closed").hasValue(20);

        HttpResponse<String> shortCircuited = get("/api/payments/42");
        assertThat(shortCircuited.statusCode()).isEqualTo(503);
        assertThat(shortCircuited.body()).contains("\"circuitOpen\":true");
        assertThat(shortCircuited.headers().firstValue("Retry-After")).hasValue("10");
        assertThat(FAILING_CALLS).as("the open breaker kept the call away from the service").hasValue(20);

        assertThat(get("/api/bookings").statusCode()).as("other services are unaffected").isEqualTo(200);
    }

    /**
     * Many slow-but-healthy calls at once are not failures. Spring Cloud CircuitBreaker adds a
     * 25-call bulkhead by default; during the payment outage drill it turned thousands of customers
     * polling their bookings into "failures" and opened booking-service's breaker.
     */
    @Test
    void manyConcurrentCallsToAHealthyServiceAreNotRejected() {
        List<CompletableFuture<Integer>> calls = IntStream.range(0, 80)
                .mapToObj(i -> http.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/queue/events/42/status"))
                        .header("Authorization", Tokens.bearer("fan-" + i)).build(), HttpResponse.BodyHandlers.discarding())
                        .thenApply(HttpResponse::statusCode))
                .toList();

        assertThat(calls.stream().map(CompletableFuture::join).toList()).containsOnly(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", Tokens.bearer("an")).build(), HttpResponse.BodyHandlers.ofString());
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static HttpServer stub(Handler handler) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                handler.handle(exchange);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String uri(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }
}
