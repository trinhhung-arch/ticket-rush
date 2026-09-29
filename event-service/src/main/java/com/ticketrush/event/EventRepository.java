package com.ticketrush.event;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface EventRepository extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {
}
