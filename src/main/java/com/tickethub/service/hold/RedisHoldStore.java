package com.tickethub.service.hold;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed holds. This is the Day 3 replacement for
 * {@link InMemoryHoldStore} and the first half of the double-booking fix.
 *
 * ── Key layout ───────────────────────────────────────────────────
 *   hold:{holdId}  -> JSON of the SeatHold, TTL = hold window
 *   seat:{seatId}  -> holdId that owns it,  TTL = hold window
 *
 * The seat keys are what make a claim exclusive. Redis expires them on
 * its own, so an abandoned checkout frees the seats with no cleanup job
 * needed on the Redis side.
 *
 * ── Why Lua ──────────────────────────────────────────────────────
 * A booking can cover several seats and must be all-or-nothing. Issuing
 * N separate SET NX calls would leave a window where another request
 * takes seat 2 between our claim of seat 1 and seat 2, and we would then
 * have to unwind. Redis executes a Lua script atomically — it is
 * single-threaded, and no other command runs mid-script — so the
 * check-all-then-claim-all happens as one indivisible step.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "tickethub.hold-store", havingValue = "redis", matchIfMissing = true)
public class RedisHoldStore implements HoldStore {

    private static final String HOLD_PREFIX = "hold:";
    private static final String SEAT_PREFIX = "seat:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RedisScript<Long> claimScript;
    private final RedisScript<Long> releaseScript;

    public RedisHoldStore(StringRedisTemplate redis,
                          ObjectMapper objectMapper,
                          @Qualifier("claimSeatsScript") RedisScript<Long> claimSeatsScript,
                          @Qualifier("releaseSeatsScript") RedisScript<Long> releaseSeatsScript) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.claimScript = claimSeatsScript;
        this.releaseScript = releaseSeatsScript;
    }

    @Override
    public boolean tryClaim(SeatHold hold) {
        List<String> seatKeys = hold.seatIds().stream()
                .map(id -> SEAT_PREFIX + id)
                .toList();

        long ttlSeconds = Math.max(1, Duration.between(Instant.now(), hold.expiresAt()).getSeconds());

        Long claimed = redis.execute(
                claimScript,
                seatKeys,
                hold.holdId(),
                String.valueOf(ttlSeconds));

        if (claimed == null || claimed == 0L) {
            return false;
        }

        // Seats are ours. Store the hold body itself, with the same TTL.
        try {
            redis.opsForValue().set(
                    HOLD_PREFIX + hold.holdId(),
                    objectMapper.writeValueAsString(hold),
                    Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException e) {
            // Serialising failed, so nobody could ever confirm this hold.
            // Give the seats straight back rather than stranding them.
            releaseSeats(hold.holdId(), hold.seatIds());
            throw new IllegalStateException("Could not serialise hold " + hold.holdId(), e);
        }
        return true;
    }

    @Override
    public Optional<SeatHold> get(String holdId) {
        String json = redis.opsForValue().get(HOLD_PREFIX + holdId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, SeatHold.class));
        } catch (JsonProcessingException e) {
            log.error("Corrupt hold payload for {}", holdId, e);
            return Optional.empty();
        }
    }

    @Override
    public void release(String holdId) {
        get(holdId).ifPresent(hold -> releaseSeats(holdId, hold.seatIds()));
        redis.delete(HOLD_PREFIX + holdId);
    }

    @Override
    public boolean isSeatClaimed(Long seatId) {
        return Boolean.TRUE.equals(redis.hasKey(SEAT_PREFIX + seatId));
    }

    @Override
    public List<Long> claimedSeats(List<Long> seatIds) {
        List<Long> claimed = new ArrayList<>();
        for (Long seatId : seatIds) {
            if (isSeatClaimed(seatId)) {
                claimed.add(seatId);
            }
        }
        return claimed;
    }

    private void releaseSeats(String holdId, List<Long> seatIds) {
        List<String> seatKeys = seatIds.stream().map(id -> SEAT_PREFIX + id).toList();
        // Deletes only the keys whose value is still our holdId. Without
        // that check we could delete a claim that a later hold took over
        // after ours expired — the classic unsafe-unlock bug.
        redis.execute(releaseScript, seatKeys, holdId);
    }
}
