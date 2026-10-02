package com.tickethub.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Record of a request that carried an Idempotency-Key header.
 *
 * A row with response_status NULL means "in flight". Once the work
 * finishes we fill in the status and body, and any later retry with the
 * same key replays that stored response instead of booking again.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idem_key", nullable = false, unique = true)
    private String idemKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256 of the request body, so key reuse with a different payload is caught. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isComplete() {
        return responseStatus != null;
    }
}
