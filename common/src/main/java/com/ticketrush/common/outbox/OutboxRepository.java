package com.ticketrush.common.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /** SKIP LOCKED lets several instances relay in parallel without sending the same row twice. */
    @Query(value = """
            select * from outbox_message
            where published_at is null
            order by seq
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxMessage> lockNextBatch(int limit);

    long countByPublishedAtIsNull();

    @Modifying
    @Query("delete from OutboxMessage m where m.publishedAt < :before")
    int deletePublishedBefore(Instant before);
}
