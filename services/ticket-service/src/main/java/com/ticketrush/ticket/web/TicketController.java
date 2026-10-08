package com.ticketrush.ticket.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ticketrush.security.Caller;
import com.ticketrush.security.CustomerOnly;
import com.ticketrush.security.OrganizerOnly;
import com.ticketrush.ticket.domain.CheckIn;
import com.ticketrush.ticket.domain.Ticket;
import com.ticketrush.ticket.domain.TicketRepository;

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

    record CheckInRequest(@NotNull UUID eventId, @NotBlank String qrToken) {
    }

    private final TicketRepository tickets;
    private final CheckIn checkIn;

    TicketController(TicketRepository tickets, CheckIn checkIn) {
        this.tickets = tickets;
        this.checkIn = checkIn;
    }

    @GetMapping
    @CustomerOnly
    @Transactional(readOnly = true)
    List<TicketView> mine(Caller caller, @RequestParam(required = false) UUID bookingId) {
        List<Ticket> found = bookingId == null
                ? tickets.findByUserIdOrderByStartsAtAscSeatCodeAsc(caller.id())
                : tickets.findByUserIdAndBookingIdOrderBySeatCode(caller.id(), bookingId);
        return found.stream().map(TicketView::of).toList();
    }

    /** FR-TKT-03: 200 ADMITTED once; a second scan gets 409 ALREADY_USED with the first check-in time. */
    @PostMapping("/check-in")
    @OrganizerOnly
    CheckIn.Admitted checkIn(Caller staff, @Valid @RequestBody CheckInRequest request) {
        return checkIn.scan(staff, request.eventId(), request.qrToken());
    }
}
