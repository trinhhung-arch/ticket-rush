package com.ticketrush.booking;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.contracts.Topics;
import com.ticketrush.contracts.payment.PaymentEvents.PaymentCreated;
import com.ticketrush.contracts.payment.PaymentEvents.PaymentFailed;
import com.ticketrush.contracts.payment.PaymentEvents.PaymentSucceeded;
import com.ticketrush.messaging.inbox.IdempotentConsumer;
import com.ticketrush.messaging.kafka.IncomingMessage;

/** Feeds payment outcomes into the saga. PaymentRefunded needs no action here. */
@Component
class PaymentEventsListener {

    private final IdempotentConsumer idempotentConsumer;
    private final BookingSaga saga;
    private final JsonMapper jsonMapper;

    PaymentEventsListener(IdempotentConsumer idempotentConsumer, BookingSaga saga, JsonMapper jsonMapper) {
        this.idempotentConsumer = idempotentConsumer;
        this.saga = saga;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS)
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record) {
        IncomingMessage message = IncomingMessage.from(record);
        boolean handled = message.is(PaymentCreated.class) || message.is(PaymentSucceeded.class)
                || message.is(PaymentFailed.class);
        if (!handled || !idempotentConsumer.firstDelivery(message)) {
            return;
        }
        if (message.is(PaymentCreated.class)) {
            saga.onPaymentCreated(message.payloadAs(PaymentCreated.class, jsonMapper));
        } else if (message.is(PaymentSucceeded.class)) {
            saga.onPaymentSucceeded(message.payloadAs(PaymentSucceeded.class, jsonMapper));
        } else {
            saga.onPaymentFailed(message.payloadAs(PaymentFailed.class, jsonMapper));
        }
    }
}
