package com.ticketrush.common.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Events ticket-service publishes on {@code ticket.events}, keyed by booking id. */
public final class TicketEvents {

    public static final int CURRENT_VERSION = 1;

    private TicketEvents() {
    }

    /** One ticket per seat of a confirmed booking (FR-TKT-01). */
    public record TicketsIssued(
            int version,
            UUID bookingId,
            UUID eventId,
            String userId,
            String email,
            String eventName,
            String venue,
            Instant startsAt,
            List<IssuedTicket> tickets) {
    }

    /** @param qrToken signed token printed in the QR code; it carries no personal data (NFR-SEC-04) */
    public record IssuedTicket(UUID ticketId, String seatCode, String qrToken) {
    }
}
