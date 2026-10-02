package com.tickethub.service;

import com.tickethub.domain.Event;
import com.tickethub.domain.Seat;
import com.tickethub.domain.SeatStatus;
import com.tickethub.repository.EventRepository;
import com.tickethub.repository.SeatRepository;
import com.tickethub.web.dto.EventResponse;
import com.tickethub.web.dto.SeatMapResponse;
import com.tickethub.web.dto.SeatResponse;
import com.tickethub.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;

    @Transactional(readOnly = true)
    public List<EventResponse> listEvents() {
        return eventRepository.findAllByOrderByStartsAtAsc().stream()
                .map(this::toEventResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("No event with id " + eventId));
        return toEventResponse(event);
    }

    @Transactional(readOnly = true)
    public SeatMapResponse getSeatMap(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("No event with id " + eventId));

        List<Seat> seats = seatRepository.findByEventIdOrderByRowLabelAscSeatNumberAsc(eventId);

        long available = seats.stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .count();

        List<SeatResponse> seatDtos = seats.stream()
                .map(s -> new SeatResponse(
                        s.getId(),
                        s.getSection(),
                        s.getRowLabel(),
                        s.getSeatNumber(),
                        s.getPriceCents(),
                        s.getStatus()))
                .toList();

        return new SeatMapResponse(
                event.getId(),
                event.getName(),
                seats.size(),
                available,
                seatDtos);
    }

    private EventResponse toEventResponse(Event event) {
        // Two counts rather than loading every seat: the event list page
        // only needs the numbers, and some events have thousands of seats.
        long available = seatRepository.countByEventIdAndStatus(event.getId(), SeatStatus.AVAILABLE);
        long held = seatRepository.countByEventIdAndStatus(event.getId(), SeatStatus.HELD);
        long booked = seatRepository.countByEventIdAndStatus(event.getId(), SeatStatus.BOOKED);

        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getVenue(),
                event.getStartsAt(),
                available + held + booked,
                available);
    }
}
