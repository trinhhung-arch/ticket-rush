package com.ticketrush.gateway;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * NFR-AVAIL-02: every call from the gateway to a service gives up after 2 s, and a service whose
 * last 20 calls failed at least half the time gets no traffic for a while, one breaker per service.
 * Callers get a quick 503 instead of piling up behind a service that is down.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResilienceConfig.ResilienceProperties.class)
class ResilienceConfig {

    @ConfigurationProperties("ticketrush.resilience")
    record ResilienceProperties(
            @DefaultValue("2s") Duration timeout,
            @DefaultValue("20") int slidingWindow,
            @DefaultValue("50") float failureRatePercent,
            @DefaultValue("10s") Duration openFor) {
    }

    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> serviceBreakers(ResilienceProperties properties) {
        CircuitBreakerConfig breaker = CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindow())
                .minimumNumberOfCalls(properties.slidingWindow())
                .failureRateThreshold(properties.failureRatePercent())
                .waitDurationInOpenState(properties.openFor())
                .permittedNumberOfCallsInHalfOpenState(5)
                .build();
        TimeLimiterConfig timeLimiter = TimeLimiterConfig.custom().timeoutDuration(properties.timeout()).build();
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(breaker)
                .timeLimiterConfig(timeLimiter)
                .build());
    }
}
