package com.ticketrush.common.messaging;

import java.util.stream.Stream;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class MessagingConfig {

    /** Creates every topic (and its dead letter topic) up front so partition counts are explicit (NFR-SCAL-02). */
    @Bean
    KafkaAdmin.NewTopics ticketRushTopics(@Value("${ticketrush.kafka.partitions:3}") int partitions,
                                          @Value("${ticketrush.kafka.replicas:1}") int replicas) {
        return new KafkaAdmin.NewTopics(Topics.ALL.stream()
                .flatMap(topic -> Stream.of(topic, topic + Topics.DEAD_LETTER_SUFFIX))
                .map(topic -> TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build())
                .toArray(NewTopic[]::new));
    }

    /**
     * Retries a failing record 3 times, one second apart, then parks it on the dead letter topic
     * so the partition keeps moving (NFR-AVAIL-03). Malformed messages skip the retries.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        var handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafkaTemplate), new FixedBackOff(1_000L, 3L));
        handler.addNotRetryableExceptions(InvalidMessageException.class, JacksonException.class);
        handler.setRetryListeners(new LoggingRetryListener());
        return handler;
    }

    /** Without this, failed deliveries are retried and dead-lettered silently. */
    static class LoggingRetryListener implements RetryListener {

        private static final Logger log = LoggerFactory.getLogger(LoggingRetryListener.class);

        @Override
        public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
            log.warn("Delivery {} of {}-{}@{} failed: {}", deliveryAttempt, record.topic(), record.partition(),
                    record.offset(), rootCause(ex).toString());
        }

        @Override
        public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
            log.error("Gave up on {}-{}@{}; it is now on the dead letter topic", record.topic(), record.partition(),
                    record.offset(), ex);
        }

        private static Throwable rootCause(Throwable ex) {
            Throwable cause = ex;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            return cause;
        }
    }
}
