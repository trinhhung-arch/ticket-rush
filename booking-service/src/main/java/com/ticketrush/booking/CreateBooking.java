package com.ticketrush.booking;

import java.util.List;
import java.util.UUID;

public record CreateBooking(String userId, String idempotencyKey, UUID eventId, List<String> seatCodes, String email) {
}
