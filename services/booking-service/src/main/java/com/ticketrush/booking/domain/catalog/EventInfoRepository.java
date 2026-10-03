package com.ticketrush.booking.domain.catalog;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventInfoRepository extends JpaRepository<EventInfo, UUID> {
}
