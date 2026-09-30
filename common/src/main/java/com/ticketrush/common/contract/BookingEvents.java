package com.ticketrush.common.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Events booking-service publishes on {@code booking.events}, keyed by booking id. */
public final class BookingEvents {

    public static final int CURRENT_VERSION = 1;

    private BookingEvents() {
    }

    /** Seats are sold and paid for; ticket-service issues the tickets. */
    public record BookingConfirmed(
            int version,
            UUID bookingId,
            UUID eventId,
            String userId,
            String email,
            String eventName,
            String venue,
            Instant startsAt,
            List<SeatLine> seats,
            long totalVnd) {
    }

    /** @param reason HOLD_EXPIRED, PAYMENT_FAILED, USER_CANCELLED or SEAT_CONFLICT */
    public record BookingCancelled(int version, UUID bookingId, UUID eventId, String userId, String email, String reason) {
    }
}
