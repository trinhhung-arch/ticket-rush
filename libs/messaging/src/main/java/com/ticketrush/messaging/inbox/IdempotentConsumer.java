package com.ticketrush.messaging.inbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ticketrush.messaging.kafka.IncomingMessage;

/**
 * Idempotent Consumer: records each message id in the handler's own transaction, so a redelivered
 * message is recognised and skipped (NFR-CORR-03). One statement: if another delivery of the same
 * message is in flight, the insert waits for it and then either conflicts or goes through.
 */
@Component
public class IdempotentConsumer {

    private final JdbcClient jdbc;

    public IdempotentConsumer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true the first time this message is seen; call it before applying any change */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(IncomingMessage message) {
        return jdbc.sql("""
                        insert into processed_message (message_id, message_type, processed_at)
                        values (:id, :type, now())
                        on conflict do nothing
                        """)
                .param("id", message.id())
                .param("type", message.type())
                .update() == 1;
    }
}
