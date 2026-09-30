package com.ticketrush.waitingroom;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/queue/events/{eventId}")
class WaitingRoomController {

    static final String USER_ID = "X-User-Id";
    private static final Duration PUSH_EVERY = Duration.ofSeconds(2);

    private final WaitingRoom room;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

    WaitingRoomController(WaitingRoom room) {
        this.room = room;
    }

    /** FR-WR-01: admitted at once while there is room, otherwise a place in line. */
    @PostMapping("/join")
    QueueStatus join(@RequestHeader(USER_ID) String userId, @PathVariable UUID eventId) {
        return room.join(eventId, userId);
    }

    @GetMapping("/status")
    QueueStatus status(@RequestHeader(USER_ID) String userId, @PathVariable UUID eventId) {
        return room.status(eventId, userId);
    }

    /** FR-WR-02: server-sent events with the position every 2 s, ending with the admission token. */
    @GetMapping("/stream")
    SseEmitter stream(@RequestHeader(USER_ID) String userId, @PathVariable UUID eventId) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        streams.submit(() -> {
            try {
                while (true) {
                    QueueStatus status = room.status(eventId, userId);
                    emitter.send(SseEmitter.event().name("status").data(status));
                    if (status.state() != QueueStatus.State.QUEUED) {
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(PUSH_EVERY);
                }
            } catch (IOException | IllegalStateException e) {
                // The browser went away; nothing left to push to.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.complete();
            }
        });
        return emitter;
    }
}
