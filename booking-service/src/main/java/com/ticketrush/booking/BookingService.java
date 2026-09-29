package com.ticketrush.booking;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketrush.booking.catalog.EventInfo;
import com.ticketrush.booking.catalog.EventInfoRepository;
import com.ticketrush.booking.seat.SeatHoldStore;
import com.ticketrush.booking.seat.SeatInventory;
import com.ticketrush.booking.seat.SeatRow;
import com.ticketrush.booking.seat.SeatStatus;
import com.ticketrush.common.web.ApiException;

/**
 * Creates bookings. The Redis hold runs outside any database transaction so a flash sale's losing
 * requests never take a connection for longer than two short reads.
 */
@Service
public class BookingService {

    /** Result of {@link #create}; {@code created} is false when an idempotent retry returned an earlier booking. */
    public record Result(BookingView booking, boolean created) {
    }

    private final BookingRepository bookings;
    private final EventInfoRepository events;
    private final SeatInventory inventory;
    private final SeatHoldStore holds;
    private final BookingProperties properties;
    private final TransactionTemplate transactionTemplate;

    BookingService(BookingRepository bookings, EventInfoRepository events, SeatInventory inventory,
                   SeatHoldStore holds, BookingProperties properties, PlatformTransactionManager transactionManager) {
        this.bookings = bookings;
        this.events = events;
        this.inventory = inventory;
        this.holds = holds;
        this.properties = properties;
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
        List<String> seatCodes = distinctSeatCodes(command.seatCodes());
        List<SeatRow> seats = inventory.findByCodes(event.id(), seatCodes);
        if (seats.size() != seatCodes.size()) {
            Set<String> known = new HashSet<>(seats.stream().map(SeatRow::code).toList());
            String unknown = seatCodes.stream().filter(code -> !known.contains(code)).findFirst().orElseThrow();
            throw ApiException.notFound("Seat %s does not exist".formatted(unknown)).with("seatCode", unknown);
        }
        seats.stream().filter(seat -> seat.status() == SeatStatus.SOLD).findFirst().ifPresent(sold -> {
            throw seatTaken(sold.code());
        });

        UUID bookingId = UUID.randomUUID();
        holds.holdAll(event.id(), seatCodes, bookingId, properties.redisHoldTtl()).ifPresent(taken -> {
            throw seatTaken(taken);
        });

        Booking booking = Booking.pending(bookingId, event.id(), command.userId(), command.email(), command.idempotencyKey(),
                seats.stream().map(seat -> new BookingSeat(seat.code(), seat.priceVnd())).toList(),
                now.plus(properties.holdDuration()), now);
        try {
            transactionTemplate.executeWithoutResult(status -> bookings.saveAndFlush(booking));
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
    public BookingView get(UUID id, String userId) {
        return bookings.findById(id)
                .filter(booking -> booking.userId().equals(userId))
                .map(BookingView::of)
                .orElseThrow(() -> ApiException.notFound("Booking %s not found".formatted(id)));
    }

    private Optional<Result> findReplay(CreateBooking command) {
        return bookings.findByIdempotencyKey(command.idempotencyKey()).map(existing -> {
            if (!existing.userId().equals(command.userId())) {
                throw ApiException.conflict("Idempotency-Key is already used by another request");
            }
            return new Result(BookingView.of(existing), false);
        });
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
