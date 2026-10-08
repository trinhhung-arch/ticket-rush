package com.ticketrush.notification;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;

/** Mailpit catches the emails the tests send: SMTP on 1025, HTTP API on 8025 for reading what was sent. */
@TestConfiguration(proxyBeanMethods = false)
class MailpitContainerConfiguration {

    @Bean
    GenericContainer<?> mailpit() {
        return new GenericContainer<>("axllent/mailpit:v1.31.4").withExposedPorts(1025, 8025);
    }

    @Bean
    DynamicPropertyRegistrar mailProperties(GenericContainer<?> mailpit) {
        return registry -> {
            registry.add("spring.mail.host", mailpit::getHost);
            registry.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
            registry.add("test.mailpit.api", () -> "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025));
        };
    }
}
