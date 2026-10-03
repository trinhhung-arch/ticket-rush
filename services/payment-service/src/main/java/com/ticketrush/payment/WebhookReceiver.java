package com.ticketrush.payment;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.ticketrush.web.ApiException;

/**
 * The one way into {@link PaymentService#handleWebhook}: the signature is checked against the raw
 * bytes before anything is parsed, so a forged body never reaches the payment logic.
 */
@Component
class WebhookReceiver {

    private final WebhookSignature signature;
    private final JsonMapper json;
    private final Validator validator;
    private final PaymentService payments;

    WebhookReceiver(WebhookSignature signature, JsonMapper json, Validator validator, PaymentService payments) {
        this.signature = signature;
        this.json = json;
        this.validator = validator;
        this.payments = payments;
    }

    PaymentView receive(String signatureHeader, byte[] body) {
        signature.verify(signatureHeader, body);
        GatewayWebhook webhook;
        try {
            webhook = json.readValue(body, GatewayWebhook.class);
        } catch (JacksonException e) {
            throw ApiException.badRequest("Webhook body is not valid JSON for a gateway webhook");
        }
        Set<ConstraintViolation<GatewayWebhook>> violations = validator.validate(webhook);
        if (!violations.isEmpty()) {
            throw ApiException.badRequest(violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", ")));
        }
        return payments.handleWebhook(webhook);
    }
}
