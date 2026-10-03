package com.ticketrush.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
class SecurityTestApplication {

    @RestController
    static class ProbeController {

        @GetMapping("/api/open")
        String open() {
            return "open";
        }

        @GetMapping("/api/me")
        Caller me(Caller caller) {
            return caller;
        }

        @PostMapping("/api/buy")
        @CustomerOnly
        String buy(Caller caller) {
            return "bought for " + caller.id();
        }

        @PostMapping("/api/publish")
        @OrganizerOnly
        String publish(Caller caller) {
            return "published by " + caller.id();
        }

        record Draft(@NotBlank String name) {
        }

        @PostMapping("/api/drafts")
        @OrganizerOnly
        String draft(@Valid @RequestBody Draft draft) {
            return draft.name();
        }
    }
}
