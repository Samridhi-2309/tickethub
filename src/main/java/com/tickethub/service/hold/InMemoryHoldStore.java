package com.tickethub.service.hold;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Heap-backed holds, kept as a no-Redis fallback for local poking.
 * Enable with tickethub.hold-store=memory.
 *
 * Note what it takes to make {@link #tryClaim} atomic here: a
 * synchronized block over the whole store, which serialises every
 * booking attempt in the application regardless of which seats are
 * involved. Redis gets the same guarantee from a Lua script without
 * blocking unrelated bookings, and across multiple app instances —
 * which this cannot do at all.
 */
@Component
@ConditionalOnProperty(name = "tickethub.hold-store", havingValue = "memory")
public class InMemoryHoldStore implements HoldStore {

    private final Map<String, SeatHold> holds = new HashMap<>();
    private final Map<Long, String> seatClaims = new HashMap<>();
    private final Object lock = new Object();

    @Override
    public boolean tryClaim(SeatHold hold) {
        synchronized (lock) {
            purgeExpired();
            for (Long seatId : hold.seatIds()) {
                if (seatClaims.containsKey(seatId)) {
                    return false;
                }
            }
            for (Long seatId : hold.seatIds()) {
                seatClaims.put(seatId, hold.holdId());
            }
            holds.put(hold.holdId(), hold);
            return true;
        }
    }

    @Override
    public Optional<SeatHold> get(String holdId) {
        synchronized (lock) {
            purgeExpired();
            return Optional.ofNullable(holds.get(holdId));
        }
    }

    @Override
    public void release(String holdId) {
        synchronized (lock) {
            SeatHold hold = holds.remove(holdId);
            if (hold != null) {
                for (Long seatId : hold.seatIds()) {
                    // Only drop the claim if it is still ours.
                    if (holdId.equals(seatClaims.get(seatId))) {
                        seatClaims.remove(seatId);
                    }
                }
            }
        }
    }

    @Override
    public boolean isSeatClaimed(Long seatId) {
        synchronized (lock) {
            purgeExpired();
            return seatClaims.containsKey(seatId);
        }
    }

    @Override
    public List<Long> claimedSeats(List<Long> seatIds) {
        synchronized (lock) {
            purgeExpired();
            List<Long> claimed = new ArrayList<>();
            for (Long seatId : seatIds) {
                if (seatClaims.containsKey(seatId)) {
                    claimed.add(seatId);
                }
            }
            return claimed;
        }
    }

    /** Redis does this with TTLs; here it has to be done by hand. */
    private void purgeExpired() {
        Instant now = Instant.now();
        List<String> dead = new ArrayList<>();
        for (SeatHold hold : holds.values()) {
            if (hold.isExpired(now)) {
                dead.add(hold.holdId());
            }
        }
        for (String holdId : dead) {
            SeatHold hold = holds.remove(holdId);
            for (Long seatId : hold.seatIds()) {
                if (holdId.equals(seatClaims.get(seatId))) {
                    seatClaims.remove(seatId);
                }
            }
        }
    }
}
