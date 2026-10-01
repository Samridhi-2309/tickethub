package com.tickethub.web.dto;

import java.time.Instant;

public record EventResponse(
        Long id,
        String name,
        String venue,
        Instant startsAt,
        long totalSeats,
        long availableSeats
) {}
