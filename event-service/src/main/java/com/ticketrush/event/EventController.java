package com.ticketrush.event;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.ticketrush.common.web.PageResponse;
import com.ticketrush.event.EventViews.EventSummary;
import com.ticketrush.event.EventViews.EventView;
import com.ticketrush.security.Caller;
import com.ticketrush.security.OrganizerOnly;

@RestController
@RequestMapping("/api/events")
class EventController {

    private final EventService events;

    EventController(EventService events) {
        this.events = events;
    }

    @PostMapping
    @OrganizerOnly
    ResponseEntity<EventView> create(Caller caller,
                                     @Valid @RequestBody EventRequest request,
                                     UriComponentsBuilder uri) {
        EventView event = events.create(caller.id(), request.toDetails());
        return ResponseEntity.created(uri.path("/api/events/{id}").build(event.id())).body(event);
    }

    @PutMapping("/{id}")
    @OrganizerOnly
    EventView update(Caller caller,
                     @PathVariable UUID id,
                     @Valid @RequestBody EventRequest request) {
        return events.update(id, caller.id(), request.toDetails());
    }

    @PostMapping("/{id}/publish")
    @OrganizerOnly
    EventView publish(Caller caller, @PathVariable UUID id) {
        return events.publish(id, caller.id());
    }

    /** Public, except that a draft is only visible to its organizer. */
    @SecurityRequirements // public: no token needed
    @GetMapping("/{id}")
    EventView get(@Nullable Caller caller, @PathVariable UUID id) {
        return events.get(id, caller == null ? null : caller.id());
    }

    @SecurityRequirements // public: no token needed
    @GetMapping
    PageResponse<EventSummary> search(@RequestParam(required = false) String city,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @PageableDefault(size = 20, sort = "startsAt") Pageable pageable) {
        return PageResponse.of(events.search(city, from, to, pageable));
    }
}
