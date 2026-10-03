package com.ticketrush.waitingroom.domain;

import java.time.Instant;

/**
 * What a buyer sees. {@code estimatedWaitSeconds} is an upper bound: every place ahead is freed at
 * the latest when that buyer's 10 minutes run out.
 */
public record QueueStatus(
        State state, Long position, Long estimatedWaitSeconds, String admissionToken, Instant admittedUntil) {

    public enum State {
        ADMITTED,
        QUEUED,
        NOT_IN_QUEUE
    }

    static QueueStatus admitted(String token, Instant until) {
        return new QueueStatus(State.ADMITTED, 0L, 0L, token, until);
    }

    static QueueStatus queued(long position, long estimatedWaitSeconds) {
        return new QueueStatus(State.QUEUED, position, estimatedWaitSeconds, null, null);
    }

    static QueueStatus notInQueue() {
        return new QueueStatus(State.NOT_IN_QUEUE, null, null, null, null);
    }
}
