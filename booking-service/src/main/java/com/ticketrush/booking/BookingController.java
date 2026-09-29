package com.ticketrush.booking;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.ticketrush.common.web.RequestHeaders;

@RestController
@RequestMapping("/api/bookings")
class BookingController {

    record CreateBookingRequest(
            @NotNull UUID eventId,
            @NotEmpty @Size(max = 6) List<@NotBlank String> seatCodes,
            @NotBlank @Email String email) {
    }

    private final BookingService bookings;

    BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    /** 201 for a new booking, 200 when the same Idempotency-Key is replayed. */
    @PostMapping
    ResponseEntity<BookingView> create(@RequestHeader(RequestHeaders.USER_ID) String userId,
                                       @RequestHeader(RequestHeaders.IDEMPOTENCY_KEY) @Size(min = 8, max = 100) String idempotencyKey,
                                       @Valid @RequestBody CreateBookingRequest request,
                                       UriComponentsBuilder uri) {
        BookingService.Result result = bookings.create(new CreateBooking(
                userId, idempotencyKey, request.eventId(), request.seatCodes(), request.email()));
        if (!result.created()) {
            return ResponseEntity.ok(result.booking());
        }
        return ResponseEntity.created(uri.path("/api/bookings/{id}").build(result.booking().id())).body(result.booking());
    }

    @GetMapping("/{id}")
    BookingView get(@RequestHeader(RequestHeaders.USER_ID) String userId, @PathVariable UUID id) {
        return bookings.get(id, userId);
    }
}
