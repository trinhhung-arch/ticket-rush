package com.ticketrush.ticket.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    boolean existsByBookingId(UUID bookingId);

    List<Ticket> findByUserIdOrderByStartsAtAscSeatCodeAsc(String userId);

    List<Ticket> findByUserIdAndBookingIdOrderBySeatCode(String userId, UUID bookingId);
}
