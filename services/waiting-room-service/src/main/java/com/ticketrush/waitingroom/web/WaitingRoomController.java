package com.ticketrush.waitingroom.web;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ticketrush.security.Caller;
import com.ticketrush.security.CustomerOnly;
import com.ticketrush.waitingroom.domain.QueueStatus;
import com.ticketrush.waitingroom.domain.WaitingRoom;

@RestController
@RequestMapping("/api/queue/events/{eventId}")
@CustomerOnly
class WaitingRoomController {
    private static final Duration PUSH_EVERY = Duration.ofSeconds(2);

    private final WaitingRoom room;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();
    /** RES-08: the one live stream per buyer and event on this instance. */
    private final Map<String, SseEmitter> openStreams = new ConcurrentHashMap<>();

    WaitingRoomController(WaitingRoom room) {
        this.room = room;
    }

    /** FR-WR-01: admitted at once while there is room, otherwise a place in line. */
    @PostMapping("/join")
    QueueStatus join(Caller caller, @PathVariable UUID eventId) {
        return room.join(eventId, caller.id());
    }

    @GetMapping("/status")
    QueueStatus status(Caller caller, @PathVariable UUID eventId) {
        return room.status(eventId, caller.id());
    }

    /**
     * FR-WR-02: server-sent events with the position every 2 s, ending with the admission token.
     *
     * <p>RES-08: every stream holds a thread and polls Redis, so a buyer gets one per event. A newer stream
     * (a reloaded tab) ends the older one rather than adding to it; the gateway limits how fast they open.
     */
    @GetMapping("/stream")
    SseEmitter stream(Caller caller, @PathVariable UUID eventId) {
        String userId = caller.id();
        String key = eventId + "/" + userId;
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        SseEmitter older = openStreams.put(key, emitter);
        if (older != null) {
            older.complete();
        }
        streams.submit(() -> {
            try {
                while (openStreams.get(key) == emitter) {
                    QueueStatus status = room.status(eventId, userId);
                    emitter.send(SseEmitter.event().name("status").data(status));
                    if (status.state() != QueueStatus.State.QUEUED) {
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(PUSH_EVERY);
                }
            } catch (IOException | IllegalStateException e) {
                // The browser went away, or a newer stream replaced this one; nothing left to push to.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } finally {
                openStreams.remove(key, emitter);
            }
        });
        return emitter;
    }
}
