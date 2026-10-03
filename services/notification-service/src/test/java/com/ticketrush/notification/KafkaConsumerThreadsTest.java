package com.ticketrush.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import com.ticketrush.TestcontainersConfiguration;

/**
 * Consumers stay on platform threads while the rest of the service uses virtual threads: on Java 21
 * a pinned consumer deadlocked this service after the host woke from sleep (common's MessagingConfig).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KafkaConsumerThreadsTest {

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Test
    void everyListenerConsumesOnAPlatformThread() {
        assertThat(listeners.getListenerContainers()).hasSizeGreaterThanOrEqualTo(2).allSatisfy(container -> {
            var executor = container.getContainerProperties().getListenerTaskExecutor();
            assertThat(executor).isNotNull();
            assertThat(executor.submit(() -> Thread.currentThread().isVirtual()).get(5, TimeUnit.SECONDS)).isFalse();
        });
    }
}
