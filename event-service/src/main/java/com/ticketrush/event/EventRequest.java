package com.ticketrush.event;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.ticketrush.common.contract.SectionSpec;

/** Body of create and update (FR-EVT-01). Cross-field rules live in {@link EventService}. */
record EventRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) String venue,
        @NotBlank @Size(max = 100) String city,
        @NotNull @Future Instant startsAt,
        @NotNull Instant salesOpenAt,
        @NotEmpty @Size(max = 20) List<@Valid SectionRequest> sections) {

    record SectionRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9]{1,8}", message = "must be 1-8 upper-case letters or digits") String code,
            @NotBlank @Size(max = 100) String name,
            @Min(1) @Max(100) int rows,
            @Min(1) @Max(100) int seatsPerRow,
            @PositiveOrZero long priceVnd) {
    }

    EventDetails toDetails() {
        return new EventDetails(name, venue, city, startsAt, salesOpenAt, sections.stream()
                .map(s -> new SectionSpec(s.code(), s.name(), s.rows(), s.seatsPerRow(), s.priceVnd()))
                .toList());
    }
}
