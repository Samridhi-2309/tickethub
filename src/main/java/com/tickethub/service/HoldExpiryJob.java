package com.tickethub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class HoldExpiryJob {

    private final BookingService bookingService;
    private final IdempotencyStore idempotencyStore;

    /**
     * Returns seats stuck at HELD whose Redis claim has expired.
     *
     * Redis frees its own keys by TTL, but nothing in Redis can update a
     * Postgres row, so the seat status needs this sweep.
     *
     * A single-instance scheduler is fine here. Several app instances
     * would need a shared lock (ShedLock or similar) so the job does not
     * run on every node at once.
     */
    @Scheduled(fixedDelayString = "${tickethub.expiry-sweep-ms:5000}")
    public void sweepHolds() {
        try {
            int released = bookingService.releaseOrphanedHolds();
            if (released > 0) {
                log.info("Expiry sweep released {} seat(s)", released);
            }
        } catch (Exception e) {
            // Never let an exception kill the scheduler thread.
            log.error("Expiry sweep failed", e);
        }
    }

    /** Idempotency records are only useful for the retention window. */
    @Scheduled(fixedDelayString = "${tickethub.idem-purge-ms:3600000}")
    public void purgeIdempotencyKeys() {
        try {
            int purged = idempotencyStore.purgeExpired();
            if (purged > 0) {
                log.info("Purged {} expired idempotency key(s)", purged);
            }
        } catch (Exception e) {
            log.error("Idempotency purge failed", e);
        }
    }
}
