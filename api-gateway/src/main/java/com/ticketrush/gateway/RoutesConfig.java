package com.ticketrush.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** The only public entry point: every /api path maps to exactly one service (FR-GW-01). */
@Configuration(proxyBeanMethods = false)
class RoutesConfig {

    @Bean
    RouteLocator routes(RouteLocatorBuilder builder, ServiceUris uris, RedisRateLimiter bookingRateLimiter,
                        KeyResolver callerKeyResolver) {
        return builder.routes()
                // The live seat map belongs to booking-service even though it reads as part of an event.
                .route("seat-map", r -> r.order(-1).path("/api/events/*/seats").uri(uris.bookingService()))
                .route("events", r -> r.path("/api/events", "/api/events/**").uri(uris.eventService()))
                // Holding seats is what bots hammer during a sale, so creating bookings is rate limited.
                .route("create-booking", r -> r.order(-1).method(HttpMethod.POST).and().path("/api/bookings")
                        .filters(f -> f.requestRateLimiter(limit -> limit
                                .setRateLimiter(bookingRateLimiter)
                                .setKeyResolver(callerKeyResolver)))
                        .uri(uris.bookingService()))
                .route("bookings", r -> r.path("/api/bookings", "/api/bookings/**").uri(uris.bookingService()))
                .route("payments", r -> r.path("/api/payments", "/api/payments/**").uri(uris.paymentService()))
                .route("tickets", r -> r.path("/api/tickets", "/api/tickets/**").uri(uris.ticketService()))
                .route("queue", r -> r.path("/api/queue", "/api/queue/**").uri(uris.waitingRoomService()))
                .build();
    }
}
