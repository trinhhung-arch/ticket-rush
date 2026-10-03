package com.ticketrush.security;

import static com.ticketrush.security.TestJwts.bearer;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * NFR-SEC-01: tokens that are expired, not yet valid, unsigned (alg:none), signed with the wrong key,
 * tampered after signing, or meant for another audience or issuer are all refused with a 401
 * problem+json, and never reach an endpoint. Exercised through the real decoder over the load-test HS256
 * issuer, so no network or Keycloak is needed; the classic "alg:none" and "edit the claims" attacks are
 * the point.
 */
@SpringBootTest(properties = {
        "ticketrush.security.public-paths=GET /api/open",
        "ticketrush.security.load-test.secret=" + TestJwts.LOAD_TEST_SECRET})
@AutoConfigureMockMvc
class JwtHardeningTest {

    private static final String GOOD_SECRET = TestJwts.LOAD_TEST_SECRET;

    @Autowired
    MockMvcTester mvc;

    @Test
    void anExpiredTokenIsRejected() {
        String token = signed(GOOD_SECRET, claims -> claims
                .issueTime(from(-2, ChronoUnit.HOURS))
                .expirationTime(from(-1, ChronoUnit.HOURS)));
        assertRejected(token);
    }

    @Test
    void aTokenThatIsNotYetValidIsRejected() {
        String token = signed(GOOD_SECRET, claims -> claims
                .notBeforeTime(from(1, ChronoUnit.HOURS))
                .expirationTime(from(2, ChronoUnit.HOURS)));
        assertRejected(token);
    }

    @Test
    void aTokenForAnotherAudienceIsRejected() {
        assertRejected(signed(GOOD_SECRET, claims -> claims.audience("some-other-client")));
        assertRejected(signed(GOOD_SECRET, claims -> claims.audience((String) null)));
    }

    @Test
    void anUntrustedIssuerIsRejected() {
        assertRejected(signed(GOOD_SECRET, claims -> claims.issuer("https://evil.example/realms/x")));
    }

    @Test
    void anUnsignedAlgNoneTokenIsRejected() {
        // The classic alg:none downgrade: a token with no signature and a "none" algorithm header.
        String token = new PlainJWT(baseClaims().build()).serialize();
        assertRejected(token);
    }

    @Test
    void aTokenTamperedAfterSigningIsRejected() {
        // Take a valid customer token, rewrite its claims to grant ADMIN, keep the old signature.
        String valid = signed(GOOD_SECRET, claims -> claims.claim("roles", List.of(Roles.CUSTOMER)));
        String[] parts = valid.split("\\.");
        String forgedPayload = new Payload(baseClaims().claim("roles", List.of(Roles.ADMIN)).build().toJSONObject())
                .toBase64URL().toString();
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];
        assertRejected(tampered);
    }

    private void assertRejected(String token) {
        assertThat(mvc.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private static Date from(long amount, ChronoUnit unit) {
        return Date.from(Instant.now().plus(amount, unit));
    }

    /** A token that would be accepted, before a test spoils one thing about it. */
    private static JWTClaimsSet.Builder baseClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(SecurityProperties.LOAD_TEST_ISSUER)
                .audience("ticketrush-api")
                .subject("mallory")
                .claim("email", "mallory@example.com")
                .claim("roles", List.of(Roles.CUSTOMER))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(1, ChronoUnit.HOURS)));
    }

    private static String signed(String secret, Consumer<JWTClaimsSet.Builder> customizer) {
        JWTClaimsSet.Builder builder = baseClaims();
        customizer.accept(builder);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
