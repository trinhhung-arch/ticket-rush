package com.ticketrush.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Callers for tests. MockMvc tests use the request post-processors, which skip signature checks;
 * tests that go over real HTTP mint a token from the load-test issuer, whose secret they configure.
 * Shipped to the services in this module's test-jar.
 */
public final class TestJwts {

    /** Configure {@code ticketrush.security.load-test.secret} with this in tests that call over HTTP. */
    public static final String LOAD_TEST_SECRET = "test-only-load-test-secret-0123456789abcdef";

    private TestJwts() {
    }

    public static RequestPostProcessor customer(String id) {
        return as(id, Roles.CUSTOMER);
    }

    public static RequestPostProcessor organizer(String id) {
        return as(id, Roles.CUSTOMER, Roles.ORGANIZER);
    }

    public static RequestPostProcessor admin(String id) {
        return as(id, Roles.CUSTOMER, Roles.ADMIN);
    }

    /** A valid token for {@code id}, with {@code id@example.com} as email and the given roles. */
    public static RequestPostProcessor as(String id, String... roles) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(id).claim("email", id + "@example.com").claim("roles", List.of(roles)))
                .authorities(Arrays.stream(roles).map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toArray(GrantedAuthority[]::new));
    }

    /** A signed bearer token from the load-test issuer, valid for one hour. */
    public static String loadTestToken(String secret, String subject, String... roles) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(SecurityProperties.LOAD_TEST_ISSUER)
                .audience("ticketrush-api")
                .subject(subject)
                .claim("email", subject + "@example.com")
                .claim("roles", List.of(roles))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }
}
