package com.ticketrush.ticket.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.contracts.Topics;
import com.ticketrush.contracts.booking.BookingEvents.BookingConfirmed;
import com.ticketrush.messaging.inbox.IdempotentConsumer;
import com.ticketrush.messaging.kafka.IncomingMessage;
import com.ticketrush.ticket.domain.TicketIssuer;

@Component
class BookingEventsListener {

    private final IdempotentConsumer idempotentConsumer;
    private final TicketIssuer issuer;
    private final JsonMapper jsonMapper;

    BookingEventsListener(IdempotentConsumer idempotentConsumer, TicketIssuer issuer, JsonMapper jsonMapper) {
        this.idempotentConsumer = idempotentConsumer;
        this.issuer = issuer;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = Topics.BOOKING_EVENTS)
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record) {
        IncomingMessage message = IncomingMessage.from(record);
        if (message.is(BookingConfirmed.class) && idempotentConsumer.firstDelivery(message)) {
            issuer.issue(message.payloadAs(BookingConfirmed.class, jsonMapper));
        }
    }
}
