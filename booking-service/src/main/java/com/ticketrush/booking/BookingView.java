package com.ticketrush.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingView(
        UUID id,
        UUID eventId,
        BookingStatus status,
        CancelReason cancelReason,
        List<BookingSeat> seats,
        long totalVnd,
        Instant expiresAt,
        Instant createdAt) {

    static BookingView of(Booking booking) {
        return new BookingView(booking.id(), booking.eventId(), booking.status(), booking.cancelReason(),
                booking.seats(), booking.totalVnd(), booking.expiresAt(), booking.createdAt());
    }
}
