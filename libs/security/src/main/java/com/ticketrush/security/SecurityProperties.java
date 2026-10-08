package com.ticketrush.security;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which tokens a service accepts and which paths stay open without one.
 *
 * @param issuer      the {@code iss} every Keycloak token must carry
 * @param jwkSetUri   where the signing keys are fetched; inside Docker or Kubernetes this is the
 *                    internal Keycloak address, while {@code issuer} stays the public one
 * @param audience    the {@code aud} value that marks a token as meant for this API
 * @param publicPaths "METHOD /pattern" or "/pattern" entries reachable without a token
 * @param loadTest    a second, HMAC-signed issuer that lets k6 act as thousands of users; off unless
 *                    a secret is set, and never set outside local load tests
 */
@ConfigurationProperties("ticketrush.security")
public record SecurityProperties(
        @DefaultValue("http://localhost:8180/realms/ticketrush") String issuer,
        @DefaultValue("http://localhost:8180/realms/ticketrush/protocol/openid-connect/certs") URI jwkSetUri,
        @DefaultValue("ticketrush-api") String audience,
        @DefaultValue List<String> publicPaths,
        @DefaultValue LoadTest loadTest) {

    public static final String LOAD_TEST_ISSUER = "ticketrush-load-test";

    public record LoadTest(String secret) {

        public boolean enabled() {
            return secret != null && !secret.isBlank();
        }
    }
}
