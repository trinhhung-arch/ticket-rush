package com.ticketrush.payment.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "payment")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private UUID bookingId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "amount_vnd", nullable = false)
    private long amountVnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "gateway_transaction_id")
    private String gatewayTransactionId;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Payment() {
    }

    static Payment open(UUID bookingId, String userId, long amountVnd, Instant expiresAt, Instant now) {
        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.bookingId = bookingId;
        payment.userId = userId;
        payment.amountVnd = amountVnd;
        payment.expiresAt = expiresAt;
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    boolean isPending() {
        return status == PaymentStatus.PENDING;
    }

    boolean isExpiredAt(Instant now) {
        return now.isAfter(expiresAt);
    }

    void succeed(String transactionId, Instant now) {
        move(PaymentStatus.PENDING, PaymentStatus.SUCCEEDED, null, now);
        this.gatewayTransactionId = transactionId;
    }

    void decline(String transactionId, Instant now) {
        move(PaymentStatus.PENDING, PaymentStatus.FAILED, "DECLINED", now);
        this.gatewayTransactionId = transactionId;
    }

    void expire(Instant now) {
        move(PaymentStatus.PENDING, PaymentStatus.EXPIRED, "EXPIRED", now);
    }

    void cancel(String reason, Instant now) {
        move(PaymentStatus.PENDING, PaymentStatus.CANCELLED, reason, now);
    }

    void refund(String reason, Instant now) {
        move(PaymentStatus.SUCCEEDED, PaymentStatus.REFUNDED, reason, now);
    }

    private void move(PaymentStatus from, PaymentStatus to, String reason, Instant now) {
        if (status != from) {
            throw new IllegalStateException("Payment %s is %s, not %s".formatted(id, status, from));
        }
        this.status = to;
        this.statusReason = reason;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID bookingId() {
        return bookingId;
    }

    public String userId() {
        return userId;
    }

    public long amountVnd() {
        return amountVnd;
    }

    public PaymentStatus status() {
        return status;
    }

    public String statusReason() {
        return statusReason;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
