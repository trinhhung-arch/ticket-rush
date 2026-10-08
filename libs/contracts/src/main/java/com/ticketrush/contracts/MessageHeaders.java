package com.ticketrush.contracts;

/** Kafka record headers every TicketRush message carries. */
public final class MessageHeaders {

    /** Unique id of the message; consumers use it to drop duplicates. */
    public static final String MESSAGE_ID = "message-id";

    /** Contract name, e.g. {@code EventPublished}; consumers dispatch on it. */
    public static final String MESSAGE_TYPE = "message-type";

    private MessageHeaders() {
    }
}
