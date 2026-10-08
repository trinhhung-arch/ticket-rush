package com.ticketrush.booking.domain;

import java.util.List;
import java.util.UUID;

/** @param admissionToken issued by the waiting room; only needed for events that use one */
public record CreateBooking(String userId, String idempotencyKey, UUID eventId, List<String> seatCodes, String email,
                            String admissionToken) {
}
