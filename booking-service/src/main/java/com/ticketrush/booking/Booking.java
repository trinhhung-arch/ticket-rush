package com.ticketrush.booking;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "booking")
public class Booking {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason")
    private CancelReason cancelReason;

    @Column(name = "total_vnd", nullable = false)
    private long totalVnd;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "checkout_url")
    private String checkoutUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "booking_seat", joinColumns = @JoinColumn(name = "booking_id"))
    @OrderBy("seatCode")
    @BatchSize(size = 50)
    private List<BookingSeat> seats = new ArrayList<>();

    protected Booking() {
    }

    /** A new booking whose seats are already held in Redis under {@code id}. */
    static Booking pending(UUID id, UUID eventId, String userId, String email, String idempotencyKey,
                           List<BookingSeat> seats, Instant expiresAt, Instant now) {
        Booking booking = new Booking();
        booking.id = id;
        booking.eventId = eventId;
        booking.userId = userId;
        booking.email = email;
        booking.idempotencyKey = idempotencyKey;
        booking.status = BookingStatus.PENDING;
        booking.seats.addAll(seats);
        booking.totalVnd = seats.stream().mapToLong(BookingSeat::priceVnd).sum();
        booking.expiresAt = expiresAt;
        booking.createdAt = now;
        booking.updatedAt = now;
        return booking;
    }

    /** PENDING or AWAITING_PAYMENT: the saga can still move either way. */
    public boolean isOpen() {
        return status == BookingStatus.PENDING || status == BookingStatus.AWAITING_PAYMENT;
    }

    void confirm(UUID paymentId, Instant now) {
        requireOpen();
        this.paymentId = paymentId;
        this.status = BookingStatus.CONFIRMED;
        this.updatedAt = now;
    }

    void cancel(CancelReason reason, Instant now) {
        requireOpen();
        this.status = BookingStatus.CANCELLED;
        this.cancelReason = reason;
        this.updatedAt = now;
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("Booking %s is already %s".formatted(id, status));
        }
    }

    public UUID id() {
        return id;
    }

    public UUID eventId() {
        return eventId;
    }

    public String userId() {
        return userId;
    }

    public String email() {
        return email;
    }

    public BookingStatus status() {
        return status;
    }

    public CancelReason cancelReason() {
        return cancelReason;
    }

    public long totalVnd() {
        return totalVnd;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public String checkoutUrl() {
        return checkoutUrl;
    }

    public List<String> seatCodes() {
        return seats.stream().map(BookingSeat::seatCode).toList();
    }

    public List<BookingSeat> seats() {
        return List.copyOf(seats);
    }
}
