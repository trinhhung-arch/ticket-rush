package com.ticketrush.payment;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByBookingId(UUID bookingId);

    Optional<Payment> findByBookingId(UUID bookingId);

    /** Webhooks and booking commands for one payment are applied one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findForUpdateById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findForUpdateByBookingId(UUID bookingId);
}
