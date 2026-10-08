package com.ticketrush.messaging.inbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/** The idempotent consumer, for every service that listens to Kafka. */
@AutoConfiguration
@Import(IdempotentConsumer.class)
public class InboxAutoConfiguration {
}
