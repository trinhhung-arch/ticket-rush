package com.ticketrush.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot builds the OpenTelemetry SDK and its OTLP log exporter, but Logback still needs to be
 * told where to send records: the OTEL appender in logback-spring.xml stays silent until installed.
 */
@Configuration(proxyBeanMethods = false)
class OpenTelemetryLoggingConfig {

    @Bean
    InitializingBean installOpenTelemetryAppender(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }
}
