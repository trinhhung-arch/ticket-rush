package com.ticketrush.booking.catalog;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventInfoRepository extends JpaRepository<EventInfo, UUID> {
}
