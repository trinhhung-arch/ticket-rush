package com.ticketrush.gateway;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** A 401 from the gateway looks like one from a service: problem+json plus the Bearer challenge (RFC 6750). */
class ProblemDetailsEntryPoint implements ServerAuthenticationEntryPoint {

    private final JsonMapper json;

    ProblemDetailsEntryPoint(JsonMapper json) {
        this.json = json;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge(exception));
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", HttpStatus.UNAUTHORIZED.getReasonPhrase());
        problem.put("status", HttpStatus.UNAUTHORIZED.value());
        problem.put("detail", "Sign in and send the access token as a Bearer token");
        problem.put("instance", exchange.getRequest().getPath().value());
        DataBuffer body = response.bufferFactory().wrap(json.writeValueAsBytes(problem));
        return response.writeWith(Mono.just(body));
    }

    /** "Bearer" when no token was sent; with error="invalid_token" when one was sent but failed. */
    private static String challenge(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth && oauth.getError() instanceof BearerTokenError error) {
            return "Bearer error=\"%s\"".formatted(error.getErrorCode());
        }
        return "Bearer";
    }
}
