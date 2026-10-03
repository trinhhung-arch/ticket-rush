package com.ticketrush;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Virtual waiting room in front of hot events (FR-WR-01 to 03). */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class WaitingRoomServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaitingRoomServiceApplication.class, args);
    }
}
