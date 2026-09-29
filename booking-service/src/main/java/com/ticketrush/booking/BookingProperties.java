package com.ticketrush.booking;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param holdDuration how long a customer has to pay (FR-BKG-02)
 * @param holdGrace    extra life of the Redis hold after that, so a payment that lands right at the
 *                     deadline does not find its seat already held by someone else
 */
@ConfigurationProperties("ticketrush.booking")
public record BookingProperties(
        @DefaultValue("PT10M") Duration holdDuration,
        @DefaultValue("PT60S") Duration holdGrace) {

    public Duration redisHoldTtl() {
        return holdDuration.plus(holdGrace);
    }
}
