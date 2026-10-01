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

    /**
     * Sweeps lapsed holds back to AVAILABLE.
     *
     * A single-instance scheduler like this is fine here; running several
     * app instances would need a shared lock (ShedLock or similar) so the
     * job does not run concurrently on every node.
     */
    @Scheduled(fixedDelayString = "${tickethub.expiry-sweep-ms:5000}")
    public void sweep() {
        try {
            int released = bookingService.releaseExpiredHolds();
            if (released > 0) {
                log.info("Expiry sweep released {} seat(s)", released);
            }
        } catch (Exception e) {
            // Never let an exception kill the scheduler thread.
            log.error("Expiry sweep failed", e);
        }
    }
}
