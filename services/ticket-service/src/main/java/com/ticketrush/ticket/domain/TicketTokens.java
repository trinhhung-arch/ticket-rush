package com.ticketrush.ticket.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.ticketrush.ticket.config.TicketProperties;

/**
 * QR token = ticket id + HMAC-SHA256 of it. Only the ticket id is inside, no personal data, and a
 * token cannot be forged without the key (FR-TKT-01, NFR-SEC-04). {@link CheckIn} verifies it.
 */
@Component
public class TicketTokens {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    TicketTokens(TicketProperties properties) {
        this.key = new SecretKeySpec(properties.signingKey().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String sign(UUID ticketId) {
        String id = ticketId.toString();
        return id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(id));
    }

    /** @return the ticket id when the token is genuine */
    public Optional<UUID> verify(String token) {
        int dot = token.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String id = token.substring(0, dot);
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(mac(id), signature)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] mac(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
