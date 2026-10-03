package com.ticketrush.booking.seat;

/** Stored seat status. A HELD seat is still AVAILABLE here; holds live in Redis. */
public enum SeatStatus {
    AVAILABLE,
    SOLD
}
