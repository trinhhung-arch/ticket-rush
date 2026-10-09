package com.ticketrush.booking.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.ticketrush.booking.domain.AdmissionTokens;
import com.ticketrush.booking.domain.BookingService;
import com.ticketrush.booking.domain.BookingView;
import com.ticketrush.booking.domain.CreateBooking;
import com.ticketrush.booking.saga.BookingSaga;
import com.ticketrush.security.Caller;
import com.ticketrush.security.CustomerOnly;
import com.ticketrush.web.ApiException;
import com.ticketrush.web.PageResponse;
import com.ticketrush.web.RequestHeaders;

@RestController
@RequestMapping("/api/bookings")
@CustomerOnly
class BookingController {

    /** Tickets go to the email on the caller's account, not to an address typed into the request. */
    record CreateBookingRequest(
            @NotNull UUID eventId,
            @NotEmpty @Size(max = 6) List<@NotBlank String> seatCodes) {
    }

    private final BookingService bookings;
    private final BookingSaga saga;

    BookingController(BookingService bookings, BookingSaga saga) {
        this.bookings = bookings;
        this.saga = saga;
    }

    /** 201 for a new booking, 200 when the same request is replayed with its Idempotency-Key, 422 for a reused key. */
    @PostMapping
    ResponseEntity<BookingView> create(Caller caller,
                                       @RequestHeader(RequestHeaders.IDEMPOTENCY_KEY) @Size(min = 8, max = 100) String idempotencyKey,
                                       @RequestHeader(value = AdmissionTokens.HEADER, required = false) String admissionToken,
                                       @Valid @RequestBody CreateBookingRequest request,
                                       UriComponentsBuilder uri) {
        if (caller.email() == null) {
            throw ApiException.unprocessable("The account has no email address to send the tickets to");
        }
        // Tickets (with their QR codes) are emailed to the account's address, so it must be a confirmed one;
        // otherwise a self-registered, unverified address could receive someone else's tickets (NFR-SEC-01).
        if (!caller.emailVerified()) {
            throw ApiException.forbidden("Verify your email address before booking; tickets are sent there");
        }
        BookingService.Result result = bookings.create(new CreateBooking(
                caller.id(), idempotencyKey, request.eventId(), request.seatCodes(), caller.email(), admissionToken));
        if (!result.created()) {
            return ResponseEntity.ok(result.booking());
        }
        return ResponseEntity.created(uri.path("/api/bookings/{id}").build(result.booking().id())).body(result.booking());
    }

    /** The caller's bookings, newest first, with cancel reasons (FR-BKG-08). */
    @GetMapping
    PageResponse<BookingView> mine(Caller caller, @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(bookings.listForUser(caller.id(), pageable));
    }

    /** FR-BKG-06: give up an unpaid booking; its seats are released at once. */
    @PostMapping("/{id}/cancel")
    BookingView cancel(Caller caller, @PathVariable UUID id) {
        return saga.cancelByCustomer(id, caller.id());
    }

    @GetMapping("/{id}")
    BookingView get(Caller caller, @PathVariable UUID id) {
        return bookings.get(id, caller.id());
    }
}
