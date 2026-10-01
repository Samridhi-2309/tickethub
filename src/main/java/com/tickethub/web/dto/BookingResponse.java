package com.tickethub.web.dto;

import com.tickethub.domain.BookingStatus;

import java.time.Instant;
import java.util.List;

public record BookingResponse(
        Long id,
        Long userId,
        Long eventId,
        BookingStatus status,
        long totalCents,
        List<Long> seatIds,
        Instant createdAt,
        Instant cancelledAt
) {}
