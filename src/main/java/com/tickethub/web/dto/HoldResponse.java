package com.tickethub.web.dto;

import java.time.Instant;
import java.util.List;

public record HoldResponse(
        String holdId,
        Long eventId,
        List<Long> seatIds,
        long totalCents,
        Instant expiresAt,
        long secondsRemaining
) {}
