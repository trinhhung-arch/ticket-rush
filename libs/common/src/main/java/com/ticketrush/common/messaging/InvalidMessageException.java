package com.ticketrush.common.messaging;

/** A message that can never be processed, so retrying it is pointless. */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }
}
