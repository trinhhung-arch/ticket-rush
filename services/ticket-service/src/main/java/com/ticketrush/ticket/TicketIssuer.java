package com.ticketrush.ticket;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketrush.common.contract.BookingEvents.BookingConfirmed;
import com.ticketrush.common.contract.SeatLine;
import com.ticketrush.common.contract.TicketEvents;
import com.ticketrush.common.contract.TicketEvents.IssuedTicket;
import com.ticketrush.common.contract.TicketEvents.TicketsIssued;
import com.ticketrush.common.messaging.Topics;
import com.ticketrush.common.outbox.OutboxWriter;

@Service
class TicketIssuer {

    private static final Logger log = LoggerFactory.getLogger(TicketIssuer.class);

    private final TicketRepository tickets;
    private final TicketTokens tokens;
    private final OutboxWriter outbox;
    private final Counter issuedTickets;

    TicketIssuer(TicketRepository tickets, TicketTokens tokens, OutboxWriter outbox, MeterRegistry meters) {
        this.tickets = tickets;
        this.tokens = tokens;
        this.outbox = outbox;
        this.issuedTickets = Counter.builder("ticketrush.tickets.issued").description("Tickets issued").register(meters);
    }

    /** One ticket per seat (FR-TKT-01). A booking is only ticketed once, even if confirmed twice. */
    @Transactional
    public void issue(BookingConfirmed booking) {
        if (tickets.existsByBookingId(booking.bookingId())) {
            return;
        }
        Instant now = Instant.now();
        List<IssuedTicket> issued = new ArrayList<>();
        for (SeatLine seat : booking.seats()) {
            UUID ticketId = UUID.randomUUID();
            String token = tokens.sign(ticketId);
            tickets.save(new Ticket(ticketId, booking.bookingId(), booking.eventId(), booking.userId(), seat.seatCode(),
                    booking.eventName(), booking.venue(), booking.startsAt(), token, now));
            issued.add(new IssuedTicket(ticketId, seat.seatCode(), token));
        }
        issuedTickets.increment(issued.size());
        log.info("Issued {} tickets for booking {}", issued.size(), booking.bookingId());
        outbox.append(Topics.TICKET_EVENTS, booking.bookingId().toString(), new TicketsIssued(
                TicketEvents.CURRENT_VERSION, booking.bookingId(), booking.eventId(), booking.userId(), booking.email(),
                booking.eventName(), booking.venue(), booking.startsAt(), issued));
    }
}
