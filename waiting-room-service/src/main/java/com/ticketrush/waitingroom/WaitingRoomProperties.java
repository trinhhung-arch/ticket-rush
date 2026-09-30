package com.ticketrush.waitingroom;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param capacity      buyers allowed on the booking pages of one event at the same time (FR-WR-01)
 * @param admissionTtl  how long an admission and its token last, matching the 10-minute seat hold
 * @param tokenKey      HS256 key shared with booking-service; injected, never committed
 * @param admitInterval how often the queue moves
 */
@ConfigurationProperties("ticketrush.waiting-room")
public record WaitingRoomProperties(
        @DefaultValue("2000") int capacity,
        @DefaultValue("PT10M") Duration admissionTtl,
        String tokenKey,
        @DefaultValue("PT1S") Duration admitInterval) {

    public WaitingRoomProperties {
        if (tokenKey == null || tokenKey.length() < 32) {
            throw new IllegalArgumentException("ticketrush.waiting-room.token-key must be at least 32 characters");
        }
    }
}
