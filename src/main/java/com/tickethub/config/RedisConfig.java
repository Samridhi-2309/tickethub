package com.tickethub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    /**
     * All-or-nothing seat claim.
     *
     * KEYS = seat:{id} for every seat in the booking
     * ARGV[1] = holdId, ARGV[2] = TTL in seconds
     *
     * Returns 1 if every key was free and all are now claimed, 0 if any
     * key already existed (and nothing was written).
     *
     * Redis runs this atomically: it is single-threaded and will not
     * interleave another client's command inside the script, so the
     * "check them all, then set them all" sequence cannot be raced.
     */
    @Bean
    public RedisScript<Long> claimSeatsScript() {
        String lua = """
                for i = 1, #KEYS do
                    if redis.call('EXISTS', KEYS[i]) == 1 then
                        return 0
                    end
                end
                for i = 1, #KEYS do
                    redis.call('SET', KEYS[i], ARGV[1], 'EX', ARGV[2])
                end
                return 1
                """;
        return new DefaultRedisScript<>(lua, Long.class);
    }

    /**
     * Release only the seats this hold still owns.
     *
     * Checking the value before deleting matters: if our hold expired and
     * a different user claimed the same seat, a blind DEL would hand their
     * seat away. Compare-then-delete inside one script avoids that.
     */
    @Bean
    public RedisScript<Long> releaseSeatsScript() {
        String lua = """
                local released = 0
                for i = 1, #KEYS do
                    if redis.call('GET', KEYS[i]) == ARGV[1] then
                        redis.call('DEL', KEYS[i])
                        released = released + 1
                    end
                end
                return released
                """;
        return new DefaultRedisScript<>(lua, Long.class);
    }
}
