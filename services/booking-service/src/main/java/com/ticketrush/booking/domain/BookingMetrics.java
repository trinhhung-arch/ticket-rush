package com.ticketrush.booking.domain;

import java.time.Duration;
import java.time.Instant;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** Business metrics of the booking flow, shown on the Grafana dashboard (NFR-OBS-02). */
@Component
public class BookingMetrics {

    private final MeterRegistry registry;
    private final Counter seatsHeld;
    private final Counter seatConflicts;
    private final Timer confirmation;

    BookingMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.seatsHeld = Counter.builder("ticketrush.seat.holds").tag("result", "held")
                .description("Hold requests, by result").register(registry);
        this.seatConflicts = Counter.builder("ticketrush.seat.holds").tag("result", "conflict")
                .description("Hold requests, by result").register(registry);
        this.confirmation = Timer.builder("ticketrush.saga.confirmation")
                .description("From the customer's payment to the booking being CONFIRMED (NFR-PERF-04: p95 <= 3 s)")
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofSeconds(1), Duration.ofSeconds(3))
                .register(registry);
    }

    void seatsHeld() {
        seatsHeld.increment();
    }

    void seatConflict() {
        seatConflicts.increment();
    }

    public void confirmed(Instant paidAt) {
        confirmation.record(Duration.between(paidAt, Instant.now()));
        completed(BookingStatus.CONFIRMED, "PAID");
    }

    public void cancelled(CancelReason reason) {
        completed(BookingStatus.CANCELLED, reason.name());
    }

    private void completed(BookingStatus status, String reason) {
        registry.counter("ticketrush.bookings.completed", "status", status.name(), "reason", reason).increment();
    }
}
