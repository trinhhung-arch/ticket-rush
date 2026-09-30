package com.ticketrush.booking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    Page<Booking> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /** Serialises the saga steps of one booking: payment events and the expiry sweep never interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Booking> findForUpdateById(UUID id);

    /** Overdue open bookings; SKIP LOCKED leaves rows another instance or a payment event is handling. */
    @Query(value = """
            select * from booking
            where status in ('PENDING', 'AWAITING_PAYMENT') and expires_at < :now
            order by expires_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<Booking> lockOverdue(Instant now, int limit);
}
