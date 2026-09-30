package com.ticketrush.ticket;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ticketrush.common.web.RequestHeaders;

/** Customers see only their own tickets (FR-TKT-02). The app renders {@code qrToken} as the QR code. */
@RestController
@RequestMapping("/api/tickets")
class TicketController {

    record TicketView(UUID id, UUID bookingId, UUID eventId, String eventName, String venue, Instant startsAt,
                      String seatCode, String qrToken, Instant checkedInAt) {

        static TicketView of(Ticket ticket) {
            return new TicketView(ticket.id(), ticket.bookingId(), ticket.eventId(), ticket.eventName(), ticket.venue(),
                    ticket.startsAt(), ticket.seatCode(), ticket.qrToken(), ticket.checkedInAt());
        }
    }

    private final TicketRepository tickets;

    TicketController(TicketRepository tickets) {
        this.tickets = tickets;
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<TicketView> mine(@RequestHeader(RequestHeaders.USER_ID) String userId,
                          @RequestParam(required = false) UUID bookingId) {
        List<Ticket> found = bookingId == null
                ? tickets.findByUserIdOrderByStartsAtAscSeatCodeAsc(userId)
                : tickets.findByUserIdAndBookingIdOrderBySeatCode(userId, bookingId);
        return found.stream().map(TicketView::of).toList();
    }
}
