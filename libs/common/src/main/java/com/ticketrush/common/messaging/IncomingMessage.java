package com.ticketrush.common.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import tools.jackson.databind.json.JsonMapper;

/** A consumed Kafka record with its TicketRush headers decoded. */
public record IncomingMessage(UUID id, String type, String key, String payload) {

    public static IncomingMessage from(ConsumerRecord<String, String> record) {
        return new IncomingMessage(
                UUID.fromString(header(record, MessageHeaders.MESSAGE_ID)),
                header(record, MessageHeaders.MESSAGE_TYPE),
                record.key(),
                record.value());
    }

    /** True when this message carries the given contract, e.g. {@code is(PaymentSucceeded.class)}. */
    public boolean is(Class<?> contract) {
        return type.equals(contract.getSimpleName());
    }

    public <T> T payloadAs(Class<T> contract, JsonMapper jsonMapper) {
        return jsonMapper.readValue(payload, contract);
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
