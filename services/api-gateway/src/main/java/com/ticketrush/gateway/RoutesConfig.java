package com.ticketrush.gateway;

import java.net.URI;
import java.util.Set;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.Buildable;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.PredicateSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * The only public entry point: every /api path maps to exactly one service (FR-GW-01), behind a
 * circuit breaker named after that service (NFR-AVAIL-02).
 */
@Configuration(proxyBeanMethods = false)
class RoutesConfig {

    /** Answers that mean the service itself is in trouble; 4xx are the caller's problem and do not count. */
    private static final Set<String> FAILURE_STATUSES = Set.of("500", "502", "503", "504");

    @Bean
    RouteLocator routes(RouteLocatorBuilder builder, ServiceUris uris, RedisRateLimiter bookingRateLimiter,
                        KeyResolver callerKeyResolver) {
        return builder.routes()
                // The live seat map belongs to booking-service even though it reads as part of an event.
                .route("seat-map", r -> r.order(-1).path("/api/events/*/seats")
                        .filters(f -> breaker(f, "booking-service")).uri(uris.bookingService()))
                .route("events", r -> r.path("/api/events", "/api/events/**")
                        .filters(f -> breaker(f, "event-service")).uri(uris.eventService()))
                // Holding seats is what bots hammer during a sale, so creating bookings is rate limited.
                .route("create-booking", r -> r.order(-1).method(HttpMethod.POST).and().path("/api/bookings")
                        .filters(f -> breaker(f.requestRateLimiter(limit -> limit
                                .setRateLimiter(bookingRateLimiter)
                                .setKeyResolver(callerKeyResolver)), "booking-service"))
                        .uri(uris.bookingService()))
                .route("bookings", r -> r.path("/api/bookings", "/api/bookings/**")
                        .filters(f -> breaker(f, "booking-service")).uri(uris.bookingService()))
                .route("payments", r -> r.path("/api/payments", "/api/payments/**")
                        .filters(f -> breaker(f, "payment-service")).uri(uris.paymentService()))
                .route("tickets", r -> r.path("/api/tickets", "/api/tickets/**")
                        .filters(f -> breaker(f, "ticket-service")).uri(uris.ticketService()))
                // The position stream stays open for minutes by design, so it has no time limit.
                .route("queue-stream", r -> r.order(-1).path("/api/queue/events/*/stream").uri(uris.waitingRoomService()))
                .route("queue", r -> r.path("/api/queue", "/api/queue/**")
                        .filters(f -> breaker(f, "waiting-room-service")).uri(uris.waitingRoomService()))
                // Each service's OpenAPI description, for the Swagger UI served by the gateway (NFR-MAINT-02).
                .route("docs-event-service", r -> docs(r, "event-service", uris.eventService()))
                .route("docs-booking-service", r -> docs(r, "booking-service", uris.bookingService()))
                .route("docs-payment-service", r -> docs(r, "payment-service", uris.paymentService()))
                .route("docs-ticket-service", r -> docs(r, "ticket-service", uris.ticketService()))
                .route("docs-waiting-room-service", r -> docs(r, "waiting-room-service", uris.waitingRoomService()))
                .build();
    }

    private static Buildable<Route> docs(PredicateSpec route, String service, URI uri) {
        return route.path("/api-docs/" + service).filters(f -> f.setPath("/v3/api-docs")).uri(uri);
    }

    private static GatewayFilterSpec breaker(GatewayFilterSpec filters, String service) {
        return filters.circuitBreaker(breaker -> breaker
                .setName(service)
                .setFallbackUri(URI.create("forward:/fallback/" + service))
                .setStatusCodes(FAILURE_STATUSES));
    }
}
