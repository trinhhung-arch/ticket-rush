package com.ticketrush.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every service checks the token itself instead of trusting the gateway (NFR-SEC-01): the gateway
 * rejects unsigned traffic early, and a service reached some other way still accepts only valid
 * Keycloak tokens. Roles are checked per endpoint with {@link CustomerOnly} and {@link OrganizerOnly}.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
class ResourceServerConfig {

    private static final String[] ALWAYS_PUBLIC = {
            "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};

    @Bean
    SecurityFilterChain api(HttpSecurity http, SecurityProperties properties, JwtDecoder jwtDecoder,
                            JsonMapper json) throws Exception {
        ProblemDetailsSecurityHandler problems = new ProblemDetailsSecurityHandler(json);
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(ALWAYS_PUBLIC).permitAll();
                    for (String entry : properties.publicPaths()) {
                        String[] parts = entry.trim().split("\\s+", 2);
                        if (parts.length == 2) {
                            requests.requestMatchers(HttpMethod.valueOf(parts[0]), parts[1]).permitAll();
                        } else {
                            requests.requestMatchers(parts[0]).permitAll();
                        }
                    }
                    requests.anyRequest().authenticated();
                })
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(authenticationConverter()))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        NimbusJwtDecoder keycloak = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString()).build();
        keycloak.setJwtValidator(validator(properties.issuer(), properties.audience()));
        if (!properties.loadTest().enabled()) {
            return keycloak;
        }
        SecretKeySpec key = new SecretKeySpec(properties.loadTest().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder loadTest = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        loadTest.setJwtValidator(validator(SecurityProperties.LOAD_TEST_ISSUER, properties.audience()));
        return new IssuerRoutingJwtDecoder(Map.of(properties.issuer(), keycloak, SecurityProperties.LOAD_TEST_ISSUER, loadTest));
    }

    @Bean
    WebMvcConfigurer callerAndRoles() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CallerArgumentResolver());
            }

            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new RoleCheckInterceptor());
            }
        };
    }

    /** Signature, expiry, issuer, and an audience that names this API rather than some other client. */
    static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
    }

    /** Keycloak's realm roles arrive flat in {@code roles}, mapped to ROLE_CUSTOMER and so on. */
    static JwtAuthenticationConverter authenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }
}
