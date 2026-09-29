package com.ticketrush.common.web;

public final class RequestHeaders {

    /** Caller identity. Set by the gateway from the JWT once Keycloak is in place (FR-IAM-01). */
    public static final String USER_ID = "X-User-Id";

    /** Client-generated key that makes a POST safe to retry (FR-BKG-04). */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private RequestHeaders() {
    }
}
