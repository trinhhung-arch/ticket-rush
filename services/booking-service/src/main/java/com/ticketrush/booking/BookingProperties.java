package com.ticketrush.booking;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param holdDuration how long a customer has to pay (FR-BKG-02)
 * @param holdGrace    extra life of the Redis hold after that, so a payment that lands right at the
 *                     deadline does not find its seat already held by someone else
 * @param maxTicketsPerCustomer tickets one account may hold or own for one event (FR-BKG-07)
 * @param admissionTokenKey     HMAC key shared with the waiting room; without it, waiting-room events refuse everyone
 */
@ConfigurationProperties("ticketrush.booking")
public record BookingProperties(
        @DefaultValue("PT10M") Duration holdDuration,
        @DefaultValue("PT60S") Duration holdGrace,
        @DefaultValue("6") int maxTicketsPerCustomer,
        @DefaultValue("") String admissionTokenKey) {

    public Duration redisHoldTtl() {
        return holdDuration.plus(holdGrace);
    }
}
