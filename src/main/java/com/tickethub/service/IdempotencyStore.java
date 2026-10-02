package com.tickethub.service;

import com.tickethub.domain.IdempotencyKey;
import com.tickethub.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Transactional storage for idempotency records.
 *
 * Two things here are deliberate and easy to get wrong.
 *
 * 1. This is a separate bean, not private methods on
 *    {@link IdempotencyService}. Spring implements @Transactional with a
 *    proxy around the bean, so a call from one method of a class to
 *    another method of the SAME class never passes through the proxy and
 *    the annotation is silently ignored. REQUIRES_NEW is the whole point
 *    — the reservation must commit immediately so other requests can see
 *    it — so the call has to cross a bean boundary.
 *
 * 2. {@link #reserve} lets the constraint violation propagate instead of
 *    catching it here. Once a flush fails, the transaction is marked
 *    rollback-only; returning normally from it would make Spring throw
 *    UnexpectedRollbackException at commit. Letting it escape lets the
 *    transaction roll back cleanly, and the caller decides what the
 *    collision means.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyStore {

    private final IdempotencyKeyRepository repository;

    private static final Duration RETENTION = Duration.ofHours(24);

    /**
     * Claim the key, or throw DataIntegrityViolationException if another
     * request already holds it.
     *
     * The unique index on idem_key is the race guard — not a prior
     * existence check, which two concurrent retries would both pass.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserve(String idemKey, Long userId, String requestHash) {
        repository.saveAndFlush(IdempotencyKey.builder()
                .idemKey(idemKey)
                .userId(userId)
                .requestHash(requestHash)
                .expiresAt(Instant.now().plus(RETENTION))
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyKey> find(String idemKey) {
        return repository.findByIdemKey(idemKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String idemKey, int status, String body) {
        repository.findByIdemKey(idemKey).ifPresent(record -> {
            record.setResponseStatus(status);
            record.setResponseBody(body);
            repository.save(record);
        });
    }

    /** A failed attempt must not block a legitimate retry. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseReservation(String idemKey) {
        repository.findByIdemKey(idemKey)
                .filter(record -> !record.isComplete())
                .ifPresent(repository::delete);
    }

    @Transactional
    public int purgeExpired() {
        return repository.deleteExpired(Instant.now());
    }
}
