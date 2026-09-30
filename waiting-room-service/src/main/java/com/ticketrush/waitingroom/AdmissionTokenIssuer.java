package com.ticketrush.waitingroom;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

/** Signs the token that lets one user book one event until their admission ends (FR-WR-03). */
@Component
class AdmissionTokenIssuer {

    static final String ISSUER = "ticketrush-waiting-room";
    static final String EVENT_CLAIM = "evt";

    private final JWSSigner signer;

    AdmissionTokenIssuer(WaitingRoomProperties properties) throws JOSEException {
        this.signer = new MACSigner(properties.tokenKey().getBytes(StandardCharsets.UTF_8));
    }

    String issue(String userId, UUID eventId, Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId)
                .claim(EVENT_CLAIM, eventId.toString())
                .issueTime(new Date())
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign admission token", e);
        }
        return jwt.serialize();
    }
}
