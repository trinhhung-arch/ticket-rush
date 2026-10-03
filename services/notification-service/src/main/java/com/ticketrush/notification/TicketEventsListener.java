package com.ticketrush.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.common.contract.TicketEvents.TicketsIssued;
import com.ticketrush.common.inbox.IdempotentConsumer;
import com.ticketrush.common.messaging.IncomingMessage;
import com.ticketrush.common.messaging.Topics;

@Component
class TicketEventsListener {

    private final IdempotentConsumer idempotentConsumer;
    private final TicketEmailSender sender;
    private final JsonMapper jsonMapper;

    TicketEventsListener(IdempotentConsumer idempotentConsumer, TicketEmailSender sender, JsonMapper jsonMapper) {
        this.idempotentConsumer = idempotentConsumer;
        this.sender = sender;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = Topics.TICKET_EVENTS)
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record) {
        IncomingMessage message = IncomingMessage.from(record);
        if (message.is(TicketsIssued.class) && idempotentConsumer.firstDelivery(message)) {
            sender.send(message.payloadAs(TicketsIssued.class, jsonMapper));
        }
    }
}
