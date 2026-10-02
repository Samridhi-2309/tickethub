package com.tickethub.service.hold;

import java.util.List;
import java.util.Optional;

/**
 * Where seat holds live.
 *
 * Day 3 rewrote this interface. Day 2's version exposed put/get/remove,
 * which forced BookingService to do "check availability, then write" in
 * two steps — the check-then-act race.
 *
 * {@link #tryClaim} replaces that with a single all-or-nothing operation:
 * either this caller gets every seat, or it gets none and somebody else
 * already holds at least one.
 */
public interface HoldStore {

    /**
     * Atomically claim every seat in the hold.
     *
     * @return true if this caller now owns all of them; false if any seat
     *         was already claimed, in which case nothing was claimed.
     */
    boolean tryClaim(SeatHold hold);

    Optional<SeatHold> get(String holdId);

    /** Drop the hold and all of its seat claims. */
    void release(String holdId);

    /**
     * Is this seat currently claimed by any live hold?
     *
     * Used by the sweeper to find seats stuck at HELD in Postgres whose
     * claim has since expired.
     */
    boolean isSeatClaimed(Long seatId);

    /** Seat ids from the given list that are currently claimed. */
    List<Long> claimedSeats(List<Long> seatIds);
}
