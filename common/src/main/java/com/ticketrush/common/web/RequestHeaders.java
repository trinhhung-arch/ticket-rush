package com.ticketrush.common.web;

public final class RequestHeaders {

    /** Client-generated key that makes a POST safe to retry (FR-BKG-04). */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private RequestHeaders() {
    }
}
