package com.ticketrush.testing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Postgres and Kafka for the services' integration tests, the same images as docker-compose.yml. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.1.0");
    }

    /** The test broker has no SASL, whatever a developer's .env asks for (see ticketrush-messaging.yml). */
    @Bean
    DynamicPropertyRegistrar plainTextKafka() {
        return registry -> registry.add("spring.kafka.security.protocol", () -> "PLAINTEXT");
    }
}
