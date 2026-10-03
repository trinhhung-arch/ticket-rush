package com.ticketrush.payment;

import java.time.Instant;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketrush.common.contract.PaymentCommands.CancelPayment;
import com.ticketrush.common.contract.PaymentCommands.CreatePayment;
import com.ticketrush.common.contract.PaymentCommands.RefundPayment;
import com.ticketrush.common.contract.PaymentEvents;
import com.ticketrush.common.contract.PaymentEvents.PaymentCreated;
import com.ticketrush.common.contract.PaymentEvents.PaymentFailed;
import com.ticketrush.common.contract.PaymentEvents.PaymentRefunded;
import com.ticketrush.common.contract.PaymentEvents.PaymentSucceeded;
import com.ticketrush.common.contract.PaymentStatusView;
import com.ticketrush.common.messaging.Topics;
import com.ticketrush.common.outbox.OutboxWriter;
import com.ticketrush.common.web.ApiException;

/**
 * Payment side of the booking saga. Every state change and the event announcing it are written in one
 * transaction, and only a PENDING payment can move, which makes commands and webhooks safe to repeat.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final OutboxWriter outbox;
    private final PaymentProperties properties;
    private final MeterRegistry meters;

    PaymentService(PaymentRepository payments, OutboxWriter outbox, PaymentProperties properties, MeterRegistry meters) {
        this.payments = payments;
        this.outbox = outbox;
        this.properties = properties;
        this.meters = meters;
    }

    /** FR-PAY-01: at most one payment per booking, however often the command arrives. */
    @Transactional
    public void create(CreatePayment command) {
        if (payments.existsByBookingId(command.bookingId())) {
            return;
        }
        Payment payment = payments.save(Payment.open(
                command.bookingId(), command.userId(), command.amountVnd(), command.expiresAt(), Instant.now()));
        record(payment, "created");
        publish(payment, new PaymentCreated(PaymentEvents.CURRENT_VERSION, payment.id(), payment.bookingId(),
                properties.checkoutUrl(payment.id()), payment.expiresAt()));
    }

    /** The booking expired first: an unpaid payment must never succeed afterwards. */
    @Transactional
    public void cancel(CancelPayment command) {
        payments.findForUpdateByBookingId(command.bookingId())
                .filter(Payment::isPending)
                .ifPresent(payment -> {
                    payment.cancel(command.reason(), Instant.now());
                    record(payment, "cancelled");
                });
        // A payment that already succeeded is left alone: the booking answers its PaymentSucceeded with a refund.
    }

    /** FR-PAY-06. Refunding twice is a no-op. */
    @Transactional
    public void refund(RefundPayment command) {
        payments.findForUpdateByBookingId(command.bookingId())
                .filter(payment -> payment.status() == PaymentStatus.SUCCEEDED)
                .ifPresent(payment -> {
                    payment.refund(command.reason(), Instant.now());
                    record(payment, "refunded");
                    publish(payment, new PaymentRefunded(PaymentEvents.CURRENT_VERSION, payment.id(),
                            payment.bookingId(), payment.amountVnd(), command.reason()));
                });
    }

    /**
     * Applies a gateway webhook (FR-PAY-02). A repeated or late webhook finds the payment no longer
     * PENDING and changes nothing (FR-PAY-03); money after the hold ended is refused (FR-PAY-05).
     */
    @Transactional
    public PaymentView handleWebhook(GatewayWebhook webhook) {
        Payment payment = payments.findForUpdateById(webhook.paymentId())
                .orElseThrow(() -> ApiException.notFound("Payment %s not found".formatted(webhook.paymentId())));
        if (!payment.isPending()) {
            return PaymentView.of(payment);
        }
        Instant now = Instant.now();
        if (payment.isExpiredAt(now)) {
            payment.expire(now);
            record(payment, "expired");
            publish(payment, new PaymentFailed(PaymentEvents.CURRENT_VERSION, payment.id(), payment.bookingId(), "EXPIRED"));
        } else if (webhook.outcome() == GatewayWebhook.Outcome.SUCCEEDED) {
            payment.succeed(webhook.transactionId(), now);
            record(payment, "succeeded");
            publish(payment, new PaymentSucceeded(PaymentEvents.CURRENT_VERSION, payment.id(), payment.bookingId(),
                    payment.amountVnd(), now));
        } else {
            payment.decline(webhook.transactionId(), now);
            record(payment, "declined");
            publish(payment, new PaymentFailed(PaymentEvents.CURRENT_VERSION, payment.id(), payment.bookingId(), "DECLINED"));
        }
        return PaymentView.of(payment);
    }

    @Transactional(readOnly = true)
    public PaymentView get(UUID id, String userId) {
        return payments.findById(id)
                .filter(payment -> payment.userId().equals(userId))
                .map(PaymentView::of)
                .orElseThrow(() -> ApiException.notFound("Payment %s not found".formatted(id)));
    }

    /** The authoritative payment state for a booking, for the booking saga to verify events against. */
    @Transactional(readOnly = true)
    public PaymentStatusView statusByBooking(UUID bookingId) {
        return payments.findByBookingId(bookingId)
                .map(payment -> new PaymentStatusView(payment.bookingId(), payment.id(), payment.status().name()))
                .orElseThrow(() -> ApiException.notFound("No payment for booking %s".formatted(bookingId)));
    }

    @Transactional(readOnly = true)
    public PaymentView getByBooking(UUID bookingId, String userId) {
        return payments.findByBookingId(bookingId)
                .filter(payment -> payment.userId().equals(userId))
                .map(PaymentView::of)
                .orElseThrow(() -> ApiException.notFound("No payment for booking %s".formatted(bookingId)));
    }

    @Transactional(readOnly = true)
    public PaymentView getForCheckout(UUID id) {
        return payments.findById(id).map(PaymentView::of)
                .orElseThrow(() -> ApiException.notFound("Payment %s not found".formatted(id)));
    }

    private void record(Payment payment, String outcome) {
        meters.counter("ticketrush.payments", "outcome", outcome).increment();
        log.info("Payment {} for booking {} {}", payment.id(), payment.bookingId(), outcome);
    }

    private void publish(Payment payment, Object event) {
        // Keyed by booking id, like booking-service's commands, so the saga sees each booking's events in order.
        outbox.append(Topics.PAYMENT_EVENTS, payment.bookingId().toString(), event);
    }
}
