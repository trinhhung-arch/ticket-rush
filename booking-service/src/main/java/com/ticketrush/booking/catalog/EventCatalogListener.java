package com.ticketrush.booking.catalog;

import java.time.Instant;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.booking.seat.SeatInventory;
import com.ticketrush.common.contract.EventPublished;
import com.ticketrush.common.inbox.IdempotentConsumer;
import com.ticketrush.common.messaging.IncomingMessage;
import com.ticketrush.common.messaging.Topics;
import com.ticketrush.common.seat.SeatLayout;

/** Builds the seat inventory of an event when event-service publishes it. */
@Component
class EventCatalogListener {

    private final IdempotentConsumer idempotentConsumer;
    private final EventInfoRepository events;
    private final SeatInventory seats;
    private final JsonMapper jsonMapper;

    EventCatalogListener(IdempotentConsumer idempotentConsumer, EventInfoRepository events, SeatInventory seats,
                         JsonMapper jsonMapper) {
        this.idempotentConsumer = idempotentConsumer;
        this.events = events;
        this.seats = seats;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = Topics.EVENT_EVENTS)
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record) {
        IncomingMessage message = IncomingMessage.from(record);
        if (!message.is(EventPublished.class) || !idempotentConsumer.firstDelivery(message)) {
            return;
        }
        EventPublished event = message.payloadAs(EventPublished.class, jsonMapper);
        // Flush now: the seat rows below go through plain JDBC and reference event_info by foreign key.
        events.saveAndFlush(EventInfo.from(event, Instant.now()));
        seats.addSeats(event.eventId(), SeatLayout.expand(event.sections()));
    }
}
