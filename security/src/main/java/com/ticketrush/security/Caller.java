package com.ticketrush.security;

import java.util.Set;

/**
 * Who is calling, taken from the verified token. Controllers declare a {@code Caller} parameter
 * instead of reading identity from headers a client could forge.
 *
 * @param id    the token subject: the Keycloak user id
 * @param email the account's email, where tickets and notices are sent
 */
public record Caller(String id, String email, Set<String> roles) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole(Roles.ADMIN);
    }
}
