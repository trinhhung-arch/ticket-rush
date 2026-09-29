package com.ticketrush.gateway;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where each downstream service lives; set per environment (NFR-DEP-04). */
@ConfigurationProperties("ticketrush.routes")
public record ServiceUris(
        URI eventService,
        URI bookingService,
        URI paymentService,
        URI ticketService,
        URI waitingRoomService) {
}
