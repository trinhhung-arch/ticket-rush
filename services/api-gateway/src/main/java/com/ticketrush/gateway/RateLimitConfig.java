package com.ticketrush.gateway;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * Token buckets in Redis, shared by every gateway instance (FR-GW-02, NFR-SEC-06). A bucket belongs
 * to the verified token's subject, or to the client IP for anonymous calls.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

    /** Primary only because the gateway's filter factory wants one default limiter; every route names its own. */
    @Bean
    @Primary
    RedisRateLimiter bookingRateLimiter(@Value("${ticketrush.rate-limit.bookings-per-second}") int perSecond) {
        // replenish = burst: at most `perSecond` requests in any one-second window, no saving up.
        return new RedisRateLimiter(perSecond, perSecond, 1);
    }

    /** RES-08: each queue stream holds a thread in the waiting room for up to 30 minutes. */
    @Bean
    RedisRateLimiter queueStreamRateLimiter(@Value("${ticketrush.rate-limit.queue-streams-per-second}") int perSecond,
                                            @Value("${ticketrush.rate-limit.queue-stream-burst}") int burst) {
        return new RedisRateLimiter(perSecond, burst, 1);
    }

    @Bean
    KeyResolver callerKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(subject -> "user:" + subject)
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                        .map(InetSocketAddress::getHostString)
                        .orElse("unknown")));
    }
}
