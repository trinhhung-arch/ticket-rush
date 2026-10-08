package com.ticketrush.security;

import java.text.ParseException;
import java.util.Map;

import com.nimbusds.jwt.JWTParser;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Hands a token to the decoder of the issuer it claims. The claim is only a routing hint: the chosen
 * decoder still verifies the signature with that issuer's key and checks {@code iss} again.
 */
final class IssuerRoutingJwtDecoder implements JwtDecoder {

    private final Map<String, JwtDecoder> decoders;

    IssuerRoutingJwtDecoder(Map<String, JwtDecoder> decoders) {
        this.decoders = Map.copyOf(decoders);
    }

    @Override
    public Jwt decode(String token) {
        String issuer;
        try {
            issuer = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new BadJwtException("Malformed token", e);
        }
        JwtDecoder decoder = issuer == null ? null : decoders.get(issuer);
        if (decoder == null) {
            throw new BadJwtException("Untrusted issuer " + issuer);
        }
        return decoder.decode(token);
    }
}
