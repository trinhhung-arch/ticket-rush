package com.ticketrush.messaging.outbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * The transactional outbox for services that publish messages: the writer, the relay to Kafka and its
 * lag metrics. A service that only consumes sets {@code ticketrush.outbox.enabled=false} and needs no
 * outbox_message table.
 *
 * <p>Registers this package for entity and repository scanning, as the services' own packages no
 * longer contain it; it must do so before Spring Data looks for repositories.
 */
@AutoConfiguration(before = DataJpaRepositoriesAutoConfiguration.class)
@AutoConfigurationPackage(basePackageClasses = OutboxMessage.class)
@ConditionalOnProperty(name = "ticketrush.outbox.enabled", havingValue = "true", matchIfMissing = true)
@Import({OutboxWriter.class, OutboxRelay.class, OutboxMetrics.class})
public class OutboxAutoConfiguration {
}
