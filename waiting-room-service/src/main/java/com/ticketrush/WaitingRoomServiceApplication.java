package com.ticketrush;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Virtual waiting room backed by a Redis sorted set. Skeleton until phase 4 (FR-WR-01 to 03). */
@SpringBootApplication
public class WaitingRoomServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaitingRoomServiceApplication.class, args);
    }
}
