package com.tickethub.web;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proves the app can actually reach Postgres and Redis, rather than just
 * reporting that the JVM is alive.
 */
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("postgres", checkPostgres());
        body.put("redis", checkRedis());
        return body;
    }

    private String checkPostgres() {
        try {
            Integer seats = jdbcTemplate.queryForObject("SELECT count(*) FROM seats", Integer.class);
            return "UP (" + seats + " seats)";
        } catch (Exception e) {
            return "DOWN: " + e.getMessage();
        }
    }

    private String checkRedis() {
        try {
            redisTemplate.opsForValue().set("tickethub:health", "ok");
            return "ok".equals(redisTemplate.opsForValue().get("tickethub:health")) ? "UP" : "DOWN";
        } catch (Exception e) {
            return "DOWN: " + e.getMessage();
        }
    }
}
