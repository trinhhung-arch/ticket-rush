package com.ticketrush.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cancels bookings whose 10 minutes ran out. Runs every 5 seconds, so a seat is free again within 30 (FR-BKG-05). */
@Component
class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);
    private static final int BATCH_SIZE = 100;

    private final BookingSaga saga;

    HoldExpirySweeper(BookingSaga saga) {
        this.saga = saga;
    }

    @Scheduled(fixedDelayString = "${ticketrush.booking.expiry-sweep-interval-ms:5000}")
    void sweep() {
        try {
            int expired;
            do {
                expired = saga.expireOverdue(BATCH_SIZE);
                if (expired > 0) {
                    log.info("Expired {} bookings", expired);
                }
            } while (expired == BATCH_SIZE);
        } catch (RuntimeException e) {
            log.warn("Expiry sweep failed, retrying on the next run: {}", e.toString());
        }
    }
}
