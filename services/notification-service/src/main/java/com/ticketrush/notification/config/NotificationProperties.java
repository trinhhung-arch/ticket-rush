package com.ticketrush.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ticketrush.mail")
public record NotificationProperties(@DefaultValue("TicketRush <no-reply@ticketrush.local>") String from) {
}
