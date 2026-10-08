package com.ticketrush.security;

/** Keycloak realm roles, carried in the token's {@code roles} claim (FR-IAM-01). */
public final class Roles {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String ORGANIZER = "ORGANIZER";
    public static final String ADMIN = "ADMIN";

    private Roles() {
    }
}
