package com.ticketrush.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.contracts.Topics;
import com.ticketrush.contracts.booking.BookingEvents.BookingCancelled;
import com.ticketrush.contracts.payment.PaymentEvents.PaymentRefunded;
import com.ticketrush.messaging.inbox.IdempotentConsumer;
import com.ticketrush.messaging.kafka.IncomingMessage;

@Component
class CancellationListener {

    private final IdempotentConsumer idempotentConsumer;
    private final CancellationNotices notices;
    private final JsonMapper jsonMapper;

    CancellationListener(IdempotentConsumer idempotentConsumer, CancellationNotices notices, JsonMapper jsonMapper) {
        this.idempotentConsumer = idempotentConsumer;
        this.notices = notices;
        this.jsonMapper = jsonMapper;
    }

    // Its own consumer group, so its rebalances never pause the ticket emails.
    @KafkaListener(topics = {Topics.BOOKING_EVENTS, Topics.PAYMENT_EVENTS}, groupId = "${spring.application.name}.cancellations")
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record) {
        IncomingMessage message = IncomingMessage.from(record);
        if (message.is(BookingCancelled.class) && idempotentConsumer.firstDelivery(message)) {
            notices.cancelled(message.payloadAs(BookingCancelled.class, jsonMapper));
        } else if (message.is(PaymentRefunded.class) && idempotentConsumer.firstDelivery(message)) {
            notices.refunded(message.payloadAs(PaymentRefunded.class, jsonMapper));
        }
    }
}
