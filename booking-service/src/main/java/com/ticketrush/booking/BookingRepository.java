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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    Page<Booking> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /** Seats a customer holds or owns for one event; cancelled bookings do not count. */
    @Query(value = """
            select count(*) from booking_seat s join booking b on b.id = s.booking_id
            where b.user_id = :userId and b.event_id = :eventId
              and b.status in ('PENDING', 'AWAITING_PAYMENT', 'CONFIRMED')
            """, nativeQuery = true)
    long countActiveSeats(String userId, UUID eventId);

    /**
     * PENDING to AWAITING_PAYMENT in one statement, no row lock or entity load: the hottest saga step
     * during a flash sale. A booking that expired first stays cancelled (0 rows).
     */
    @Modifying
    @Query("""
            update Booking b
            set b.status = :awaiting, b.paymentId = :paymentId, b.checkoutUrl = :checkoutUrl,
                b.updatedAt = :now, b.version = b.version + 1
            where b.id = :id and b.status = :pending
            """)
    int markAwaitingPayment(UUID id, UUID paymentId, String checkoutUrl, Instant now,
                            BookingStatus pending, BookingStatus awaiting);

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
