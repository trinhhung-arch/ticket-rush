package com.ticketrush.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ticketrush.booking.catalog.EventInfo;
import com.ticketrush.booking.catalog.EventInfoRepository;
import com.ticketrush.booking.seat.SeatHoldStore;
import com.ticketrush.booking.seat.SeatInventory;
import com.ticketrush.booking.seat.SeatRow;
import com.ticketrush.booking.seat.SeatStatus;
import com.ticketrush.common.contract.BookingEvents;
import com.ticketrush.common.contract.BookingEvents.BookingCancelled;
import com.ticketrush.common.contract.BookingEvents.BookingConfirmed;
import com.ticketrush.common.contract.PaymentCommands;
import com.ticketrush.common.contract.PaymentCommands.CancelPayment;
import com.ticketrush.common.contract.PaymentCommands.RefundPayment;
import com.ticketrush.common.contract.PaymentEvents.PaymentCreated;
import com.ticketrush.common.contract.PaymentEvents.PaymentFailed;
import com.ticketrush.common.contract.PaymentEvents.PaymentSucceeded;
import com.ticketrush.common.contract.SeatLine;
import com.ticketrush.common.messaging.Topics;
import com.ticketrush.common.outbox.OutboxWriter;
import com.ticketrush.common.web.ApiException;

/**
 * Orchestrates the booking saga (FR-BKG-09, ADR 0001). Every step locks the booking row first, so
 * payment events and the expiry sweep for the same booking are applied one at a time, and every
 * branch ends in CONFIRMED or CANCELLED with the customer's money either kept for a seat or refunded.
 */
@Service
public class BookingSaga {

    private static final Logger log = LoggerFactory.getLogger(BookingSaga.class);

    private final BookingRepository bookings;
    private final EventInfoRepository events;
    private final SeatInventory inventory;
    private final SeatHoldStore holds;
    private final OutboxWriter outbox;
    private final BookingMetrics metrics;

    BookingSaga(BookingRepository bookings, EventInfoRepository events, SeatInventory inventory, SeatHoldStore holds,
                OutboxWriter outbox, BookingMetrics metrics) {
        this.bookings = bookings;
        this.events = events;
        this.inventory = inventory;
        this.holds = holds;
        this.outbox = outbox;
        this.metrics = metrics;
    }

    @Transactional
    public void onPaymentCreated(PaymentCreated event) {
        // A booking that expired before its payment was created stays cancelled; CancelPayment is already on its way.
        int updated = bookings.markAwaitingPayment(event.bookingId(), event.paymentId(), event.checkoutUrl(), Instant.now(),
                BookingStatus.PENDING, BookingStatus.AWAITING_PAYMENT);
        if (updated == 1) {
            log.info("Booking {} is awaiting payment {}", event.bookingId(), event.paymentId());
        }
    }

    /** Confirms the booking, or refunds when it can no longer be honoured (FR-PAY-06, NFR-AVAIL-05). */
    @Transactional
    public void onPaymentSucceeded(PaymentSucceeded event) {
        Booking booking = bookings.findForUpdateById(event.bookingId())
                .orElseThrow(() -> new IllegalStateException("Payment for unknown booking " + event.bookingId()));
        switch (booking.status()) {
            case CONFIRMED -> log.info("Booking {} is already confirmed; ignoring payment {}", booking.id(), event.paymentId());
            case CANCELLED -> refund(booking, "BOOKING_" + booking.cancelReason());
            case PENDING, AWAITING_PAYMENT -> confirmOrRefund(booking, event);
        }
    }

    @Transactional
    public void onPaymentFailed(PaymentFailed event) {
        CancelReason reason = "EXPIRED".equals(event.reason()) ? CancelReason.HOLD_EXPIRED : CancelReason.PAYMENT_FAILED;
        bookings.findForUpdateById(event.bookingId())
                .filter(Booking::isOpen)
                .ifPresent(booking -> cancel(booking, reason, Instant.now()));
    }

    /**
     * The customer gives up an unpaid booking (FR-BKG-06): the seats are free at once and the payment
     * is stopped. Cancelling twice is fine; a paid booking cannot be cancelled here.
     */
    @Transactional
    public BookingView cancelByCustomer(UUID bookingId, String userId) {
        Booking booking = bookings.findForUpdateById(bookingId)
                .filter(found -> found.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Booking %s not found".formatted(bookingId)));
        if (booking.status() == BookingStatus.CONFIRMED) {
            throw ApiException.conflict("Booking %s is already paid".formatted(bookingId));
        }
        if (booking.isOpen()) {
            cancel(booking, CancelReason.USER_CANCELLED, Instant.now());
            outbox.append(Topics.PAYMENT_COMMANDS, booking.id().toString(),
                    new CancelPayment(PaymentCommands.CURRENT_VERSION, booking.id(), CancelReason.USER_CANCELLED.name()));
        }
        return BookingView.of(booking);
    }

    /**
     * Cancels up to {@code limit} bookings whose hold ran out (FR-BKG-05) and tells payment-service to
     * stop accepting their payments.
     *
     * @return how many bookings were expired
     */
    @Transactional
    public int expireOverdue(int limit) {
        Instant now = Instant.now();
        List<Booking> overdue = bookings.lockOverdue(now, limit);
        for (Booking booking : overdue) {
            cancel(booking, CancelReason.HOLD_EXPIRED, now);
            outbox.append(Topics.PAYMENT_COMMANDS, booking.id().toString(),
                    new CancelPayment(PaymentCommands.CURRENT_VERSION, booking.id(), CancelReason.HOLD_EXPIRED.name()));
        }
        return overdue.size();
    }

    private void confirmOrRefund(Booking booking, PaymentSucceeded payment) {
        Instant now = Instant.now();
        List<String> seatCodes = booking.seatCodes();
        List<SeatRow> seats = inventory.lockForSale(booking.eventId(), seatCodes);
        boolean allAvailable = seats.size() == seatCodes.size()
                && seats.stream().allMatch(seat -> seat.status() == SeatStatus.AVAILABLE);
        if (!allAvailable) {
            // Redis lost this booking's hold and another booking paid for a seat first: the database wins.
            log.warn("Booking {} paid for a seat that is already sold; cancelling and refunding", booking.id());
            cancel(booking, CancelReason.SEAT_CONFLICT, now);
            refund(booking, CancelReason.SEAT_CONFLICT.name());
            return;
        }
        inventory.markSold(booking.eventId(), seatCodes, booking.id());
        booking.confirm(payment.paymentId(), now);
        metrics.confirmed(payment.paidAt());
        log.info("Booking {} confirmed: seats {} sold", booking.id(), seatCodes);

        EventInfo event = events.findById(booking.eventId()).orElseThrow();
        outbox.append(Topics.BOOKING_EVENTS, booking.id().toString(), new BookingConfirmed(
                BookingEvents.CURRENT_VERSION, booking.id(), booking.eventId(), booking.userId(), booking.email(),
                event.name(), event.venue(), event.startsAt(),
                booking.seats().stream().map(seat -> new SeatLine(seat.seatCode(), seat.priceVnd())).toList(),
                booking.totalVnd()));
        // The Redis holds are left to expire: from now on the SOLD rows keep the seats out of reach.
    }

    private void cancel(Booking booking, CancelReason reason, Instant now) {
        booking.cancel(reason, now);
        metrics.cancelled(reason);
        log.info("Booking {} cancelled: {}", booking.id(), reason);
        outbox.append(Topics.BOOKING_EVENTS, booking.id().toString(), new BookingCancelled(
                BookingEvents.CURRENT_VERSION, booking.id(), booking.eventId(), booking.userId(), booking.email(),
                reason.name()));
        afterCommit(() -> holds.releaseAll(booking.eventId(), booking.seatCodes(), booking.id()));
    }

    private void refund(Booking booking, String reason) {
        log.info("Booking {} asks for a refund: {}", booking.id(), reason);
        outbox.append(Topics.PAYMENT_COMMANDS, booking.id().toString(),
                new RefundPayment(PaymentCommands.CURRENT_VERSION, booking.id(), reason));
    }

    /** Releasing a hold is only safe once the cancellation is committed; if Redis is down the hold simply expires. */
    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    log.warn("Could not release seat holds, they will expire on their own: {}", e.toString());
                }
            }
        });
    }
}
