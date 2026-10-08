package com.ticketrush.gateway;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.List;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jwt.JWTParser;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway turns away requests without a valid Keycloak token before they reach a service
 * (NFR-SEC-01) and forwards the token unchanged. Roles are the services' business: each one checks
 * the token again and decides per endpoint.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityConfig.TokenProperties.class)
class SecurityConfig {

    static final String LOAD_TEST_ISSUER = "ticketrush-load-test";

    /** Same keys and meaning as the services' ticketrush.security settings. */
    @ConfigurationProperties("ticketrush.security")
    record TokenProperties(String issuer, String jwkSetUri, @DefaultValue("ticketrush-api") String audience,
                           @DefaultValue LoadTest loadTest) {

        record LoadTest(String secret) {

            boolean enabled() {
                return secret != null && !secret.isBlank();
            }
        }
    }

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder, JsonMapper json) {
        ProblemDetailsEntryPoint problems = new ProblemDetailsEntryPoint(json);
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Browsing events and seat maps needs no account (FR-EVT-03, FR-BKG-01).
                        .pathMatchers(HttpMethod.GET, "/api/events", "/api/events/**").permitAll()
                        // The payment provider's checkout page and its webhooks are signed, not logged in (FR-PAY-04).
                        .pathMatchers(HttpMethod.POST, "/api/payments/*/checkout", "/api/payments/webhooks/**").permitAll()
                        .pathMatchers("/api/**").authenticated()
                        // Everything else is actuator health or a path with no route, which answers 404.
                        .anyExchange().permitAll())
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder))
                        .authenticationEntryPoint(problems))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(TokenProperties properties) {
        NimbusReactiveJwtDecoder keycloak = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        keycloak.setJwtValidator(validator(properties.issuer(), properties.audience()));
        if (!properties.loadTest().enabled()) {
            return keycloak;
        }
        SecretKeySpec key = new SecretKeySpec(properties.loadTest().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusReactiveJwtDecoder loadTest = NimbusReactiveJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        loadTest.setJwtValidator(validator(LOAD_TEST_ISSUER, properties.audience()));
        Map<String, ReactiveJwtDecoder> byIssuer = Map.of(properties.issuer(), keycloak, LOAD_TEST_ISSUER, loadTest);
        // The issuer claim only picks the decoder; that decoder still checks the signature and the issuer.
        return token -> {
            String issuer;
            try {
                issuer = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
            } catch (ParseException e) {
                return Mono.error(new BadJwtException("Malformed token", e));
            }
            ReactiveJwtDecoder decoder = issuer == null ? null : byIssuer.get(issuer);
            return decoder == null ? Mono.error(new BadJwtException("Untrusted issuer " + issuer)) : decoder.decode(token);
        };
    }

    private static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
    }
}
