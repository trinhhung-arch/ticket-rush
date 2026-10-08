package com.ticketrush.contracts;

import java.util.List;

/** Kafka topics shared by all services: one topic per publishing service and message kind. */
public final class Topics {

    public static final String EVENT_EVENTS = "event.events";
    public static final String BOOKING_EVENTS = "booking.events";
    public static final String PAYMENT_COMMANDS = "payment.commands";
    public static final String PAYMENT_EVENTS = "payment.events";
    public static final String TICKET_EVENTS = "ticket.events";

    /** Suffix spring-kafka's DeadLetterPublishingRecoverer appends by default. */
    public static final String DEAD_LETTER_SUFFIX = "-dlt";

    public static final List<String> ALL =
            List.of(EVENT_EVENTS, BOOKING_EVENTS, PAYMENT_COMMANDS, PAYMENT_EVENTS, TICKET_EVENTS);

    private Topics() {
    }
}
