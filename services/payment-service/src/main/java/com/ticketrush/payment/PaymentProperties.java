package com.ticketrush.payment;

import java.time.Duration;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param publicBaseUrl where customers reach the platform, used to build checkout links
 * @param webhook       the secret shared with the payment provider and how old a signed webhook may be
 */
@ConfigurationProperties("ticketrush.payment")
public record PaymentProperties(@DefaultValue("http://localhost:8080") String publicBaseUrl,
                                @DefaultValue Webhook webhook) {

    public record Webhook(String secret, @DefaultValue("PT5M") Duration tolerance) {
    }

    String checkoutUrl(UUID paymentId) {
        return publicBaseUrl + "/api/payments/" + paymentId + "/checkout";
    }
}
