package com.ticketrush.payment;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.common.contract.PaymentCommands.CancelPayment;
import com.ticketrush.common.contract.PaymentCommands.CreatePayment;
import com.ticketrush.common.contract.PaymentCommands.RefundPayment;
import com.ticketrush.common.inbox.IdempotentConsumer;
import com.ticketrush.common.messaging.IncomingMessage;
import com.ticketrush.common.messaging.Topics;

@Component
class PaymentCommandsListener {

    private final IdempotentConsumer idempotentConsumer;
    private final PaymentService payments;
    private final JsonMapper jsonMapper;

    PaymentCommandsListener(IdempotentConsumer idempotentConsumer, PaymentService payments, JsonMapper jsonMapper) {
        this.idempotentConsumer = idempotentConsumer;
        this.payments = payments;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = Topics.PAYMENT_COMMANDS)
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record) {
        IncomingMessage message = IncomingMessage.from(record);
        boolean handled = message.is(CreatePayment.class) || message.is(CancelPayment.class)
                || message.is(RefundPayment.class);
        if (!handled || !idempotentConsumer.firstDelivery(message)) {
            return;
        }
        if (message.is(CreatePayment.class)) {
            payments.create(message.payloadAs(CreatePayment.class, jsonMapper));
        } else if (message.is(CancelPayment.class)) {
            payments.cancel(message.payloadAs(CancelPayment.class, jsonMapper));
        } else {
            payments.refund(message.payloadAs(RefundPayment.class, jsonMapper));
        }
    }
}
