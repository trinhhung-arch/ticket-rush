package com.ticketrush.booking.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketrush.booking.config.BookingProperties;
import com.ticketrush.booking.domain.catalog.EventInfo;
import com.ticketrush.booking.domain.catalog.EventInfoRepository;
import com.ticketrush.booking.domain.seat.SeatHoldStore;
import com.ticketrush.booking.domain.seat.SeatInventory;
import com.ticketrush.booking.domain.seat.SeatRow;
import com.ticketrush.booking.domain.seat.SeatStatus;
import com.ticketrush.contracts.Topics;
import com.ticketrush.contracts.payment.PaymentCommands;
import com.ticketrush.contracts.payment.PaymentCommands.CreatePayment;
import com.ticketrush.messaging.outbox.OutboxWriter;
import com.ticketrush.web.ApiException;

/**
 * Creates bookings. The Redis hold runs outside any database transaction so a flash sale's losing
 * requests never take a connection for longer than two short reads.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    /** Result of {@link #create}; {@code created} is false when an idempotent retry returned an earlier booking. */
    public record Result(BookingView booking, boolean created) {
    }

    private final BookingRepository bookings;
    private final EventInfoRepository events;
    private final SeatInventory inventory;
    private final SeatHoldStore holds;
    private final BookingProperties properties;
    private final OutboxWriter outbox;
    private final BookingMetrics metrics;
    private final JdbcClient jdbc;
    private final AdmissionTokens admissionTokens;
    private final TransactionTemplate transactionTemplate;

    BookingService(BookingRepository bookings, EventInfoRepository events, SeatInventory inventory,
                   SeatHoldStore holds, BookingProperties properties, OutboxWriter outbox, BookingMetrics metrics,
                   JdbcClient jdbc, AdmissionTokens admissionTokens, PlatformTransactionManager transactionManager) {
        this.bookings = bookings;
        this.events = events;
        this.inventory = inventory;
        this.holds = holds;
        this.properties = properties;
        this.outbox = outbox;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.admissionTokens = admissionTokens;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Holds 1-6 seats for 10 minutes, all or nothing (FR-BKG-02, FR-BKG-03, FR-BKG-04). */
    public Result create(CreateBooking command) {
        Optional<Result> replay = findReplay(command);
        if (replay.isPresent()) {
            return replay.get();
        }

        Instant now = Instant.now();
        EventInfo event = events.findById(command.eventId())
                .orElseThrow(() -> ApiException.notFound("Event %s is not on sale".formatted(command.eventId())));
        if (!event.isOnSale(now)) {
            throw ApiException.conflict("Sales for event %s are not open".formatted(event.id()))
                    .with("salesOpenAt", event.salesOpenAt());
        }
        if (event.waitingRoom() && !admissionTokens.admits(command.admissionToken(), command.userId(), event.id())) {
            throw ApiException.forbidden("Event %s sells through the waiting room; join the queue first".formatted(event.id()))
                    .with("joinQueue", "/api/queue/events/" + event.id() + "/join");
        }
        List<String> seatCodes = distinctSeatCodes(command.seatCodes());
        List<SeatRow> seats = inventory.findByCodes(event.id(), seatCodes);
        if (seats.size() != seatCodes.size()) {
            Set<String> known = new HashSet<>(seats.stream().map(SeatRow::code).toList());
            String unknown = seatCodes.stream().filter(code -> !known.contains(code)).findFirst().orElseThrow();
            throw ApiException.notFound("Seat %s does not exist".formatted(unknown)).with("seatCode", unknown);
        }
        seats.stream().filter(seat -> seat.status() == SeatStatus.SOLD).findFirst().ifPresent(sold -> {
            metrics.seatConflict();
            throw seatTaken(sold.code());
        });

        UUID bookingId = UUID.randomUUID();
        holds.holdAll(event.id(), seatCodes, bookingId, properties.redisHoldTtl()).ifPresent(taken -> {
            metrics.seatConflict();
            throw seatTaken(taken);
        });
        metrics.seatsHeld();

        Booking booking = Booking.pending(bookingId, event.id(), command.userId(), command.email(), command.idempotencyKey(),
                seats.stream().map(seat -> new BookingSeat(seat.code(), seat.priceVnd())).toList(),
                now.plus(properties.holdDuration()), now);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                enforceTicketLimit(command.userId(), event.id(), seatCodes.size());
                bookings.saveAndFlush(booking);
                // First saga step: ask payment-service for a payment, atomically with the booking row.
                outbox.append(Topics.PAYMENT_COMMANDS, bookingId.toString(), new CreatePayment(
                        PaymentCommands.CURRENT_VERSION, bookingId, command.userId(), booking.totalVnd(), booking.expiresAt()));
            });
            log.info("Booking {} holds {} for user {}", bookingId, seatCodes, command.userId());
            return new Result(BookingView.of(booking), true);
        } catch (DataIntegrityViolationException e) {
            // Another request with the same Idempotency-Key won the insert; hand back its booking.
            holds.releaseAll(event.id(), seatCodes, bookingId);
            return findReplay(command).orElseThrow(() -> e);
        } catch (RuntimeException e) {
            holds.releaseAll(event.id(), seatCodes, bookingId);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Page<BookingView> listForUser(String userId, Pageable pageable) {
        return bookings.findByUserIdOrderByCreatedAtDesc(userId, pageable).map(BookingView::of);
    }

    @Transactional(readOnly = true)
    public BookingView get(UUID id, String userId) {
        return bookings.findById(id)
                .filter(booking -> booking.userId().equals(userId))
                .map(BookingView::of)
                .orElseThrow(() -> ApiException.notFound("Booking %s not found".formatted(id)));
    }

    /**
     * FR-BKG-07. The advisory lock serialises one customer's requests for one event, so parallel
     * requests cannot each see room for their seats and together go over the limit.
     */
    private void enforceTicketLimit(String userId, UUID eventId, int requested) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "ticket-limit:" + userId + ":" + eventId)
                .query().listOfRows();
        long active = bookings.countActiveSeats(userId, eventId);
        int limit = properties.maxTicketsPerCustomer();
        if (active + requested > limit) {
            throw ApiException.unprocessable("At most %d tickets per customer for this event; you already have %d"
                    .formatted(limit, active)).with("limit", limit).with("current", active);
        }
    }

    private Optional<Result> findReplay(CreateBooking command) {
        return bookings.findByIdempotencyKey(command.idempotencyKey()).map(existing -> {
            if (!existing.userId().equals(command.userId())) {
                throw ApiException.conflict("Idempotency-Key is already used by another request");
            }
            // BIZ-07: a key names one request. Replaying the booking for other seats would tell the client
            // that seats it never got are held.
            if (!isSameRequest(existing, command)) {
                throw ApiException.unprocessable("Idempotency-Key was already used with a different request body");
            }
            return new Result(BookingView.of(existing), false);
        });
    }

    private static boolean isSameRequest(Booking existing, CreateBooking command) {
        return existing.eventId().equals(command.eventId())
                && existing.seatCodes().stream().sorted().toList().equals(command.seatCodes().stream().sorted().toList());
    }

    private static List<String> distinctSeatCodes(List<String> seatCodes) {
        List<String> distinct = seatCodes.stream().distinct().sorted().toList();
        if (distinct.size() != seatCodes.size()) {
            throw ApiException.badRequest("seatCodes contains duplicates");
        }
        return distinct;
    }

    private static ApiException seatTaken(String seatCode) {
        return ApiException.conflict("Seat %s is no longer available".formatted(seatCode)).with("seatCode", seatCode);
    }
}
