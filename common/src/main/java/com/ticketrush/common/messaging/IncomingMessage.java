package com.ticketrush.common.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** A consumed Kafka record with its TicketRush headers decoded. */
public record IncomingMessage(UUID id, String type, String key, String payload) {

    public static IncomingMessage from(ConsumerRecord<String, String> record) {
        return new IncomingMessage(
                UUID.fromString(header(record, MessageHeaders.MESSAGE_ID)),
                header(record, MessageHeaders.MESSAGE_TYPE),
                record.key(),
                record.value());
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) {
            throw new InvalidMessageException(
                    "Missing header '%s' on %s-%d@%d".formatted(name, record.topic(), record.partition(), record.offset()));
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
