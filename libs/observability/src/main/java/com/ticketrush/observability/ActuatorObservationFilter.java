package com.ticketrush.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prometheus scrapes /actuator/prometheus every few seconds; without this, each scrape becomes a
 * trace and buries the real ones. The HTTP metrics for those requests are dropped too.
 */
@Configuration(proxyBeanMethods = false)
class ActuatorObservationFilter {

    private static final String ACTUATOR = "/actuator";

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = Type.SERVLET)
    static class Servlet {

        @Bean
        ObservationPredicate ignoreActuatorRequests() {
            return (name, context) -> !(context instanceof org.springframework.http.server.observation.ServerRequestObservationContext request
                    && request.getCarrier().getRequestURI().startsWith(ACTUATOR));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = Type.REACTIVE)
    static class Reactive {

        @Bean
        ObservationPredicate ignoreActuatorRequests() {
            return (name, context) -> !(context instanceof org.springframework.http.server.reactive.observation.ServerRequestObservationContext request
                    && request.getCarrier().getPath().value().startsWith(ACTUATOR));
        }
    }
}
