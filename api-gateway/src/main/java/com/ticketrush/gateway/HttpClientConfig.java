package com.ticketrush.gateway;

import java.time.Duration;

import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reactor Netty caches DNS answers; when a service container is recreated or scaled out it gets a
 * new address and the gateway would keep calling the old one ("connection refused"). A short cache
 * lets new and replaced instances be picked up within seconds, and round-robin selection spreads
 * connections over every address a scaled service resolves to (NFR-SCAL-01, NFR-AVAIL-04).
 */
@Configuration(proxyBeanMethods = false)
class HttpClientConfig {

    @Bean
    HttpClientCustomizer shortDnsCache() {
        return client -> client.resolver(spec -> spec
                .roundRobinSelection(true)
                .cacheMaxTimeToLive(Duration.ofSeconds(10))
                .cacheNegativeTimeToLive(Duration.ofSeconds(1)));
    }
}
