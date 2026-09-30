package com.ticketrush.payment;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * What the payment gateway posts back once the customer has paid or been declined.
 * Signing it with HMAC comes in phase 5 (FR-PAY-04).
 */
public record GatewayWebhook(@NotNull UUID paymentId, @NotBlank String transactionId, @NotNull Outcome outcome) {

    public enum Outcome {
        SUCCEEDED,
        DECLINED
    }
}
