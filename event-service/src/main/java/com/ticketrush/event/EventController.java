package com.ticketrush.event;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.ticketrush.common.web.PageResponse;
import com.ticketrush.common.web.RequestHeaders;
import com.ticketrush.event.EventViews.EventSummary;
import com.ticketrush.event.EventViews.EventView;

@RestController
@RequestMapping("/api/events")
class EventController {

    private final EventService events;

    EventController(EventService events) {
        this.events = events;
    }

    @PostMapping
    ResponseEntity<EventView> create(@RequestHeader(RequestHeaders.USER_ID) String userId,
                                     @Valid @RequestBody EventRequest request,
                                     UriComponentsBuilder uri) {
        EventView event = events.create(userId, request.toDetails());
        return ResponseEntity.created(uri.path("/api/events/{id}").build(event.id())).body(event);
    }

    @PutMapping("/{id}")
    EventView update(@RequestHeader(RequestHeaders.USER_ID) String userId,
                     @PathVariable UUID id,
                     @Valid @RequestBody EventRequest request) {
        return events.update(id, userId, request.toDetails());
    }

    @PostMapping("/{id}/publish")
    EventView publish(@RequestHeader(RequestHeaders.USER_ID) String userId, @PathVariable UUID id) {
        return events.publish(id, userId);
    }

    @GetMapping("/{id}")
    EventView get(@RequestHeader(value = RequestHeaders.USER_ID, required = false) String userId, @PathVariable UUID id) {
        return events.get(id, userId);
    }

    @GetMapping
    PageResponse<EventSummary> search(@RequestParam(required = false) String city,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @PageableDefault(size = 20, sort = "startsAt") Pageable pageable) {
        return PageResponse.of(events.search(city, from, to, pageable));
    }
}
