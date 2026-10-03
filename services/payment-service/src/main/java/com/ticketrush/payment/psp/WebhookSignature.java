package com.ticketrush.payment.psp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.ticketrush.payment.config.PaymentProperties;
import com.ticketrush.web.ApiException;

/**
 * FR-PAY-04, NFR-SEC-02: a webhook carries {@code X-Webhook-Signature: t=<unix seconds>,v1=<hex>}, where
 * v1 is HMAC-SHA256 over {@code "<t>.<raw body>"} with the secret shared with the provider (the scheme
 * Stripe uses). The timestamp is inside the MAC, so an old webhook cannot be replayed with a fresh one,
 * and anything signed more than five minutes ago is refused.
 */
@Component
public class WebhookSignature {

    public static final String HEADER = "X-Webhook-Signature";

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private final SecretKeySpec key;
    private final Duration tolerance;

    public WebhookSignature(PaymentProperties properties) {
        String secret = properties.webhook().secret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "ticketrush.payment.webhook.secret (PAYMENT_WEBHOOK_SECRET) must be at least 32 characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.tolerance = properties.webhook().tolerance();
    }

    public String sign(byte[] body, Instant signedAt) {
        long timestamp = signedAt.getEpochSecond();
        return "t=" + timestamp + ",v1=" + HEX.formatHex(mac(timestamp, body));
    }

    /** Throws a 401 problem unless the header is a valid, recent signature of exactly this body. */
    void verify(String header, byte[] body) {
        if (header == null || header.isBlank()) {
            throw ApiException.unauthorized("Missing " + HEADER + " header");
        }
        Long timestamp = null;
        byte[] given = null;
        try {
            for (String part : header.split(",")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals("t")) {
                    timestamp = Long.parseLong(pair[1]);
                } else if (pair.length == 2 && pair[0].equals("v1")) {
                    given = HEX.parseHex(pair[1]);
                }
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized("Malformed " + HEADER + " header");
        }
        if (timestamp == null || given == null) {
            throw ApiException.unauthorized("Malformed " + HEADER + " header");
        }
        // Constant-time comparison, so response timing reveals nothing about the right signature.
        if (!MessageDigest.isEqual(mac(timestamp, body), given)) {
            throw ApiException.unauthorized("Webhook signature does not match");
        }
        Duration age = Duration.between(Instant.ofEpochSecond(timestamp), Instant.now()).abs();
        if (age.compareTo(tolerance) > 0) {
            throw ApiException.unauthorized("Webhook was signed %d s ago; the limit is %d s"
                    .formatted(age.toSeconds(), tolerance.toSeconds()));
        }
    }

    private byte[] mac(long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
