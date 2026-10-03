package com.ticketrush.waitingroom.domain;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Lets people in as places free up. Safe to run on every instance: each move is one Lua script. */
@Component
class AdmissionLoop {

    private static final Logger log = LoggerFactory.getLogger(AdmissionLoop.class);

    private final WaitingRoom room;

    AdmissionLoop(WaitingRoom room) {
        this.room = room;
    }

    @Scheduled(fixedDelayString = "${ticketrush.waiting-room.admit-interval:PT1S}")
    void admit() {
        try {
            for (String event : room.eventsWithQueues()) {
                UUID eventId = UUID.fromString(event);
                long admitted = room.admitNext(eventId);
                if (admitted > 0) {
                    log.info("Admitted {} buyers to event {}", admitted, eventId);
                }
                room.forgetIfEmpty(eventId);
            }
        } catch (RuntimeException e) {
            log.warn("Admission round failed, retrying on the next one: {}", e.toString());
        }
    }
}
