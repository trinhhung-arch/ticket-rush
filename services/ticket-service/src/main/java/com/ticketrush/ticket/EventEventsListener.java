package com.ticketrush.ticket;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.contracts.Topics;
import com.ticketrush.contracts.event.EventPublished;
import com.ticketrush.messaging.kafka.IncomingMessage;

/** Learns each event's organizer, so check-in can be limited to them (FR-TKT-03). */
@Component
class EventEventsListener {

    private final EventOrganizers organizers;
    private final JsonMapper jsonMapper;

    EventEventsListener(EventOrganizers organizers, JsonMapper jsonMapper) {
        this.organizers = organizers;
        this.jsonMapper = jsonMapper;
    }

    // Its own consumer group, so its rebalances never pause ticket issuing. Low volume: two threads.
    @KafkaListener(topics = Topics.EVENT_EVENTS, groupId = "${spring.application.name}.event-organizers", concurrency = "2")
    public void onMessage(ConsumerRecord<String, String> record) {
        IncomingMessage message = IncomingMessage.from(record);
        if (!message.is(EventPublished.class)) {
            return;
        }
        EventPublished event = message.payloadAs(EventPublished.class, jsonMapper);
        if (event.organizerId() != null) {
            organizers.remember(event.eventId(), event.organizerId());
        }
    }
}
