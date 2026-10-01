package com.tickethub.web.dto;

import java.util.List;

public record SeatMapResponse(
        Long eventId,
        String eventName,
        long totalSeats,
        long availableSeats,
        List<SeatResponse> seats
) {}
