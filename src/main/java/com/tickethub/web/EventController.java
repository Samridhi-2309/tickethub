package com.tickethub.web;

import com.tickethub.service.EventService;
import com.tickethub.web.dto.EventResponse;
import com.tickethub.web.dto.SeatMapResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public List<EventResponse> list() {
        return eventService.listEvents();
    }

    @GetMapping("/{eventId}")
    public EventResponse get(@PathVariable Long eventId) {
        return eventService.getEvent(eventId);
    }

    @GetMapping("/{eventId}/seats")
    public SeatMapResponse seatMap(@PathVariable Long eventId) {
        return eventService.getSeatMap(eventId);
    }
}
