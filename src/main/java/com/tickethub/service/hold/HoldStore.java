package com.tickethub.service.hold;

import java.util.List;
import java.util.Optional;

/**
 * Where seat holds live.
 *
 * Day 2 ships an in-memory implementation. Day 3 replaces it with a Redis
 * implementation — the interface exists so that swap is a one-line change
 * in configuration rather than a rewrite of the booking service.
 */
public interface HoldStore {

    void put(SeatHold hold);

    Optional<SeatHold> get(String holdId);

    void remove(String holdId);

    /** Holds whose expiry has passed and whose seats need releasing. */
    List<SeatHold> findExpired();
}
