package com.ticketrush.waitingroom;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * FIFO queue per event, kept in Redis so any number of instances share it (FR-WR-01). Each step is a
 * Lua script, so a place is never skipped or given twice. Keys carry the event id as a hash tag and
 * stay valid on Redis Cluster; the index of events with a queue lives in its own key.
 */
@Service
public class WaitingRoom {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> JOIN = RedisScript.of(new ClassPathResource("redis/join-queue.lua"), List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> STATUS = RedisScript.of(new ClassPathResource("redis/queue-status.lua"), List.class);
    private static final RedisScript<Long> ADMIT = RedisScript.of(new ClassPathResource("redis/admit-next.lua"), Long.class);
    private static final String EVENTS_WITH_QUEUES = "wr:events";

    private final StringRedisTemplate redis;
    private final WaitingRoomProperties properties;
    private final AdmissionTokenIssuer tokens;
    private final Counter joins;
    private final Counter admissions;

    WaitingRoom(StringRedisTemplate redis, WaitingRoomProperties properties, AdmissionTokenIssuer tokens,
                MeterRegistry meters) {
        this.redis = redis;
        this.properties = properties;
        this.tokens = tokens;
        this.joins = Counter.builder("ticketrush.waiting.room.joins").register(meters);
        this.admissions = Counter.builder("ticketrush.waiting.room.admissions").register(meters);
    }

    public QueueStatus join(UUID eventId, String userId) {
        joins.increment();
        List<?> result = redis.execute(JOIN, List.of(active(eventId), queue(eventId), sequence(eventId)),
                userId, now(), Integer.toString(properties.capacity()), Long.toString(properties.admissionTtl().toMillis()));
        return toStatus(eventId, userId, result);
    }

    public QueueStatus status(UUID eventId, String userId) {
        List<?> result = redis.execute(STATUS, List.of(active(eventId), queue(eventId)), userId, now());
        return toStatus(eventId, userId, result);
    }

    /** @return how many buyers were let in */
    long admitNext(UUID eventId) {
        Long admitted = redis.execute(ADMIT, List.of(active(eventId), queue(eventId)),
                now(), Integer.toString(properties.capacity()), Long.toString(properties.admissionTtl().toMillis()));
        long count = admitted == null ? 0 : admitted;
        admissions.increment(count);
        return count;
    }

    Set<String> eventsWithQueues() {
        return redis.opsForSet().members(EVENTS_WITH_QUEUES);
    }

    void forgetIfEmpty(UUID eventId) {
        Long waiting = redis.opsForZSet().zCard(queue(eventId));
        if (waiting == null || waiting == 0) {
            redis.opsForSet().remove(EVENTS_WITH_QUEUES, eventId.toString());
        }
    }

    private QueueStatus toStatus(UUID eventId, String userId, List<?> result) {
        long state = ((Number) result.get(0)).longValue();
        long value = ((Number) result.get(1)).longValue();
        if (state == 1) {
            Instant until = Instant.ofEpochMilli(value);
            return QueueStatus.admitted(tokens.issue(userId, eventId, until), until);
        }
        if (state == 0) {
            // Re-registering on every poll keeps the admission loop aware of this queue even if a
            // concurrent cleanup dropped it from the index.
            redis.opsForSet().add(EVENTS_WITH_QUEUES, eventId.toString());
            long rounds = (value + properties.capacity() - 1) / properties.capacity();
            return QueueStatus.queued(value, rounds * properties.admissionTtl().toSeconds());
        }
        return QueueStatus.notInQueue();
    }

    private static String now() {
        return Long.toString(System.currentTimeMillis());
    }

    private static String active(UUID eventId) {
        return "wr:{" + eventId + "}:active";
    }

    private static String queue(UUID eventId) {
        return "wr:{" + eventId + "}:queue";
    }

    private static String sequence(UUID eventId) {
        return "wr:{" + eventId + "}:seq";
    }
}
