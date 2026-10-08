package com.ticketrush.ticket.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketrush.security.Caller;
import com.ticketrush.web.ApiException;

/**
 * FR-TKT-03: gate staff scan the QR code. A ticket gets in once: the conditional update lets exactly
 * one of any number of simultaneous scans through, and every later scan is told when it was used.
 */
@Service
public class CheckIn {

    public enum Result {
        ADMITTED
    }

    public record Admitted(Result result, UUID ticketId, String seatCode, String eventName, Instant checkedInAt) {
    }

    private final TicketTokens tokens;
    private final TicketRepository tickets;
    private final EventOrganizers organizers;
    private final JdbcClient jdbc;

    CheckIn(TicketTokens tokens, TicketRepository tickets, EventOrganizers organizers, JdbcClient jdbc) {
        this.tokens = tokens;
        this.tickets = tickets;
        this.organizers = organizers;
        this.jdbc = jdbc;
    }

    @Transactional
    public Admitted scan(Caller staff, UUID eventId, String qrToken) {
        UUID ticketId = tokens.verify(qrToken)
                .orElseThrow(() -> ApiException.unprocessable("Not a genuine TicketRush QR code").with("result", "INVALID"));
        if (!staff.isAdmin() && !organizers.organizerOf(eventId).map(staff.id()::equals).orElse(false)) {
            throw ApiException.forbidden("Only the organizer of event %s can check its tickets in".formatted(eventId));
        }
        Ticket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> ApiException.notFound("Ticket %s not found".formatted(ticketId)));
        if (!ticket.eventId().equals(eventId)) {
            throw ApiException.conflict("This ticket is for another event").with("result", "WRONG_EVENT");
        }
        Instant now = Instant.now();
        int admitted = jdbc.sql("""
                        update ticket set checked_in_at = :now, checked_in_by = :staff
                        where id = :id and checked_in_at is null
                        """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("staff", staff.id())
                .param("id", ticketId)
                .update();
        if (admitted == 0) {
            Instant firstUse = jdbc.sql("select checked_in_at from ticket where id = :id")
                    .param("id", ticketId).query(OffsetDateTime.class).single().toInstant();
            throw ApiException.conflict("Ticket already used").with("result", "ALREADY_USED").with("checkedInAt", firstUse);
        }
        return new Admitted(Result.ADMITTED, ticket.id(), ticket.seatCode(), ticket.eventName(), now);
    }
}
