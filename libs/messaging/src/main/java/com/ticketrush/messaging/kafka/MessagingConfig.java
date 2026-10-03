package com.ticketrush.messaging.kafka;

import java.util.Map;
import java.util.stream.Stream;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

import com.ticketrush.contracts.Topics;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class MessagingConfig {

    /**
     * Creates every topic (and its dead letter topic) up front so partition counts are explicit
     * (NFR-SCAL-02). 12 partitions leave room for 6 listener threads on each of 2 instances; a flash
     * sale's 10,000 payment events drained too slowly on 3.
     */
    @Bean
    KafkaAdmin.NewTopics ticketRushTopics(@Value("${ticketrush.kafka.partitions:12}") int partitions,
                                          @Value("${ticketrush.kafka.replicas:1}") int replicas) {
        return new KafkaAdmin.NewTopics(Topics.ALL.stream()
                .flatMap(topic -> Stream.of(topic, topic + Topics.DEAD_LETTER_SUFFIX))
                .map(topic -> TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build())
                .toArray(NewTopic[]::new));
    }

    /**
     * The classic consumer group protocol, with a 60 s poll interval. The poll interval is also the
     * rebalance timeout: after the broker restarted in the Kafka outage drill, a rebalance waited the
     * default 5 minutes for members that never rejoined, and tickets stalled that long. A batch here
     * takes seconds, so 60 s is still ample. Each listener that is not a service's main one has its
     * own consumer group, so no group mixes two subscriptions.
     *
     * <p>Not the Kafka 4 consumer protocol (KIP-848): with kafka-clients 4.1.2 its background threads
     * spun at ~250 % CPU per service while the broker was down, and seat holds timed out in the
     * drill. {@code ticketrush.kafka.group-protocol=consumer} switches it on.
     */
    @Bean
    DefaultKafkaConsumerFactoryCustomizer consumerGroupProtocol(
            @Value("${ticketrush.kafka.group-protocol:classic}") String protocol) {
        Map<String, Object> config = "classic".equals(protocol)
                ? Map.of(ConsumerConfig.GROUP_PROTOCOL_CONFIG, protocol, ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 60_000)
                : Map.of(ConsumerConfig.GROUP_PROTOCOL_CONFIG, protocol);
        return factory -> factory.updateConfigs(config);
    }

    /**
     * Listener consumers run on platform threads, although the services otherwise use virtual
     * threads. kafka-clients logs from inside {@code synchronized} methods, which pins a virtual
     * thread to its carrier on Java 21. After the laptop woke from sleep, every consumer in
     * notification-service logged a poll timeout at once: 12 pinned consumers waited for logback's
     * lock while the virtual thread it was handed to had no free carrier, and the service stopped
     * until restarted. A consumer is one long-lived thread, so virtual threads gave nothing here.
     */
    @Bean
    ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> platformConsumerThreads() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("kafka-");
        return container -> container.getContainerProperties().setListenerTaskExecutor(executor);
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
