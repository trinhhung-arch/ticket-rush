package com.ticketrush.payment;

import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param publicBaseUrl where customers reach the platform, used to build checkout links */
@ConfigurationProperties("ticketrush.payment")
public record PaymentProperties(@DefaultValue("http://localhost:8080") String publicBaseUrl) {

    String checkoutUrl(UUID paymentId) {
        return publicBaseUrl + "/api/payments/" + paymentId + "/checkout";
    }
}
