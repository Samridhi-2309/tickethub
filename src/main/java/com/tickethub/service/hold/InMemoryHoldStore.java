package com.tickethub.service.hold;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Day 2 implementation: a map in the JVM heap.
 *
 * Deliberately naive, and inadequate for two reasons worth being able to
 * state out loud:
 *   1. It dies with the process, so a restart silently loses every hold
 *      while the seats stay marked HELD in Postgres.
 *   2. It is per-instance, so two app instances behind a load balancer
 *      would not see each other's holds.
 *
 * Day 3 replaces this with Redis, which fixes both.
 */
@Component
public class InMemoryHoldStore implements HoldStore {

    private final Map<String, SeatHold> holds = new ConcurrentHashMap<>();

    @Override
    public void put(SeatHold hold) {
        holds.put(hold.holdId(), hold);
    }

    @Override
    public Optional<SeatHold> get(String holdId) {
        return Optional.ofNullable(holds.get(holdId));
    }

    @Override
    public void remove(String holdId) {
        holds.remove(holdId);
    }

    @Override
    public List<SeatHold> findExpired() {
        Instant now = Instant.now();
        List<SeatHold> expired = new ArrayList<>();
        for (SeatHold hold : holds.values()) {
            if (hold.isExpired(now)) {
                expired.add(hold);
            }
        }
        return expired;
    }
}
