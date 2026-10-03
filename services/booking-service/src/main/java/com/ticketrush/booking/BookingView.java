package com.ticketrush.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** @param checkoutUrl where the customer pays; set once the booking is AWAITING_PAYMENT */
public record BookingView(
        UUID id,
        UUID eventId,
        BookingStatus status,
        CancelReason cancelReason,
        List<BookingSeat> seats,
        long totalVnd,
        Instant expiresAt,
        UUID paymentId,
        String checkoutUrl,
        Instant createdAt) {

    static BookingView of(Booking booking) {
        return new BookingView(booking.id(), booking.eventId(), booking.status(), booking.cancelReason(),
                booking.seats(), booking.totalVnd(), booking.expiresAt(), booking.paymentId(), booking.checkoutUrl(),
                booking.createdAt());
    }
}
