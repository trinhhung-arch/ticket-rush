package com.ticketrush.payment;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * What the payment gateway posts back once the customer has paid or been declined.
 * The provider signs it with HMAC (FR-PAY-04); see {@link WebhookSignature}.
 */
public record GatewayWebhook(@NotNull UUID paymentId, @NotBlank String transactionId, @NotNull Outcome outcome) {

    public enum Outcome {
        SUCCEEDED,
        DECLINED
    }
}
