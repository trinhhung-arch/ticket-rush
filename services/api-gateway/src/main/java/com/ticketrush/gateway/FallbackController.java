package com.ticketrush.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/** Where a tripped or timed-out circuit breaker sends the request: a problem+json 503 or 504. */
@Hidden
@RestController
class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @RequestMapping("/fallback/{service}")
    ResponseEntity<Map<String, Object>> unavailable(@PathVariable String service, ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        boolean timedOut = cause instanceof TimeoutException;
        if (!(cause instanceof CallNotPermittedException)) {
            // Each of these counts against the breaker, so say what it was.
            log.warn("{} call failed at the gateway: {}", service, cause);
        }
        HttpStatus status = timedOut ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE;
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", timedOut
                ? "%s did not answer within the time limit; retrying with the same Idempotency-Key is safe".formatted(service)
                : "%s is unavailable right now; try again shortly".formatted(service));
        problem.put("service", service);
        problem.put("circuitOpen", cause instanceof CallNotPermittedException);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (!timedOut) {
            response.header("Retry-After", "10");
        }
        return response.body(problem);
    }
}
