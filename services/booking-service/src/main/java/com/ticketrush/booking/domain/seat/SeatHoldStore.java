package com.ticketrush.booking.domain.seat;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Seat holds in Redis: the fast path that absorbs a flash sale before anything touches the database.
 * Keys carry the event id as a hash tag, so all seats of one event share a Redis Cluster slot and
 * the Lua scripts stay valid on a cluster (NFR-SCAL-03).
 */
@Component
public class SeatHoldStore {

    private static final RedisScript<Long> HOLD =
            RedisScript.of(new ClassPathResource("redis/hold-seats.lua"), Long.class);
    private static final RedisScript<Long> RELEASE =
            RedisScript.of(new ClassPathResource("redis/release-seats.lua"), Long.class);

    private final StringRedisTemplate redis;

    SeatHoldStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return the first seat already held by another booking, or empty when every seat is now held */
    public Optional<String> holdAll(UUID eventId, List<String> seatCodes, UUID bookingId, Duration ttl) {
        Long conflict = redis.execute(HOLD, keys(eventId, seatCodes), bookingId.toString(), Long.toString(ttl.toMillis()));
        if (conflict == null || conflict == 0) {
            return Optional.empty();
        }
        return Optional.of(seatCodes.get(conflict.intValue() - 1));
    }

    public void releaseAll(UUID eventId, List<String> seatCodes, UUID bookingId) {
        redis.execute(RELEASE, keys(eventId, seatCodes), bookingId.toString());
    }

    /** One MGET for the whole seat map. */
    public Set<String> heldAmong(UUID eventId, List<String> seatCodes) {
        List<String> owners = redis.opsForValue().multiGet(keys(eventId, seatCodes));
        Set<String> held = new HashSet<>();
        for (int i = 0; owners != null && i < owners.size(); i++) {
            if (owners.get(i) != null) {
                held.add(seatCodes.get(i));
            }
        }
        return held;
    }

    static String key(UUID eventId, String seatCode) {
        return "hold:{" + eventId + "}:" + seatCode;
    }

    private static List<String> keys(UUID eventId, List<String> seatCodes) {
        return seatCodes.stream().map(code -> key(eventId, code)).toList();
    }
}
