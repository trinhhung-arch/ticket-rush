package com.ticketrush.booking.domain;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import com.ticketrush.booking.config.BookingProperties;

/**
 * Checks the JWT the waiting room hands out when a buyer's turn comes (FR-WR-03): signed with the
 * shared HS256 key, issued to this user for this event, not expired.
 */
@Component
public class AdmissionTokens {

    public static final String HEADER = "X-Admission-Token";
    static final String ISSUER = "ticketrush-waiting-room";
    static final String EVENT_CLAIM = "evt";

    private final JWSVerifier verifier;

    AdmissionTokens(BookingProperties properties) throws JOSEException {
        String key = properties.admissionTokenKey();
        this.verifier = key.isBlank() ? null : new MACVerifier(key.getBytes(StandardCharsets.UTF_8));
    }

    public boolean admits(String token, String userId, UUID eventId) {
        if (verifier == null || token == null || token.isBlank()) {
            return false;
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
                return false;
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expiresAt = claims.getExpirationTime();
            return ISSUER.equals(claims.getIssuer())
                    && userId.equals(claims.getSubject())
                    && eventId.toString().equals(claims.getStringClaim(EVENT_CLAIM))
                    && expiresAt != null && expiresAt.toInstant().isAfter(Instant.now());
        } catch (ParseException | JOSEException e) {
            return false;
        }
    }
}
