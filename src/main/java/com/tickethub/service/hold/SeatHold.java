package com.tickethub.service.hold;

import java.time.Instant;
import java.util.List;

/**
 * A short-lived claim on a set of seats, created when a user starts
 * checkout and released if they do not confirm in time.
 */
public record SeatHold(
        String holdId,
        Long userId,
        Long eventId,
        List<Long> seatIds,
        long totalCents,
        Instant expiresAt
) {
    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
