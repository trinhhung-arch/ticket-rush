package com.ticketrush.ticket;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param signingKey HMAC key for QR tokens; injected from the environment, never committed (NFR-SEC-03) */
@ConfigurationProperties("ticketrush.ticket")
public record TicketProperties(String signingKey) {

    public TicketProperties {
        if (signingKey == null || signingKey.length() < 32) {
            throw new IllegalArgumentException("ticketrush.ticket.signing-key must be at least 32 characters");
        }
    }
}
