package com.ticketrush.ticket;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** ticket-service's own record of who organizes each event; it never reads event-service's database. */
@Repository
class EventOrganizers {

    private final JdbcClient jdbc;

    EventOrganizers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An upsert, so a redelivered EventPublished changes nothing. */
    void remember(UUID eventId, String organizerId) {
        jdbc.sql("""
                        insert into event_organizer (event_id, organizer_id) values (:eventId, :organizerId)
                        on conflict (event_id) do update set organizer_id = excluded.organizer_id
                        """)
                .param("eventId", eventId)
                .param("organizerId", organizerId)
                .update();
    }

    Optional<String> organizerOf(UUID eventId) {
        return jdbc.sql("select organizer_id from event_organizer where event_id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .optional();
    }
}
