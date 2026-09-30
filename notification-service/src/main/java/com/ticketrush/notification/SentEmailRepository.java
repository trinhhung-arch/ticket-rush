package com.ticketrush.notification;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SentEmailRepository extends JpaRepository<SentEmail, UUID> {

    boolean existsByBookingIdAndKind(UUID bookingId, String kind);
}
