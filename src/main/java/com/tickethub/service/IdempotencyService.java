package com.tickethub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.domain.IdempotencyKey;
import com.tickethub.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Makes a request safe to retry.
 *
 * Why booking needs this: the connection drops after the server commits
 * the booking but before the response reaches the client. The client
 * retries. Without a guard the user is charged twice and holds two sets
 * of seats. With an Idempotency-Key the retry replays the first response.
 *
 * Storage lives in {@link IdempotencyStore} — see the note there on why
 * it has to be a separate bean.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public <T> T execute(String idemKey,
                         Long userId,
                         Object requestBody,
                         Class<T> responseType,
                         Supplier<T> action) {

        if (idemKey == null || idemKey.isBlank()) {
            return action.get();
        }

        String requestHash = sha256(toJson(requestBody));

        boolean weOwnIt;
        try {
            store.reserve(idemKey, userId, requestHash);
            weOwnIt = true;
        } catch (DataIntegrityViolationException e) {
            // Someone else inserted this key first. The reserve transaction
            // rolled back cleanly; read their row in a fresh one.
            weOwnIt = false;
        }

        if (!weOwnIt) {
            IdempotencyKey record = store.find(idemKey)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "REQUEST_IN_PROGRESS",
                            "A request with this Idempotency-Key is already in progress"));

            if (!record.getRequestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request body");
            }
            if (!record.isComplete()) {
                // First attempt still running. Asking the client to retry
                // is safer than running the booking a second time.
                throw new ApiException(HttpStatus.CONFLICT,
                        "REQUEST_IN_PROGRESS",
                        "A request with this Idempotency-Key is already in progress");
            }

            log.info("Replaying stored response for idempotency key {}", idemKey);
            return fromJson(record.getResponseBody(), responseType);
        }

        try {
            T result = action.get();
            store.complete(idemKey, HttpStatus.CREATED.value(), toJson(result));
            return result;
        } catch (RuntimeException e) {
            store.releaseReservation(idemKey);
            throw e;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise for idempotency", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read stored idempotent response", e);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
