package com.ticketrush.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Bearer tokens from the load-test issuer, which the gateway tests switch on with {@link #SECRET}. */
final class Tokens {

    static final String SECRET = "gateway-test-load-test-secret-0123456789abcdef";
    static final String SECRET_PROPERTY = "ticketrush.security.load-test.secret=" + SECRET;

    private Tokens() {
    }

    static String bearer(String subject) {
        return bearer(subject, SECRET);
    }

    static String bearer(String subject, String secret) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(SecurityConfig.LOAD_TEST_ISSUER)
                .audience("ticketrush-api")
                .subject(subject)
                .claim("roles", List.of("CUSTOMER"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return "Bearer " + jwt.serialize();
    }
}
