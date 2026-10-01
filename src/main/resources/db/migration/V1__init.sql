-- ─────────────────────────────────────────────────────────────
-- V1: core schema
-- Flyway owns the schema; Hibernate is set to `validate` only.
-- ─────────────────────────────────────────────────────────────

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE events (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    venue      VARCHAR(255) NOT NULL,
    starts_at  TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_events_starts_at ON events (starts_at);

-- One row per physical seat. `status` is the authoritative record of
-- whether a seat is free; Redis holds are a fast-path optimisation on top.
CREATE TABLE seats (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT       NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    section     VARCHAR(16)  NOT NULL,
    row_label   VARCHAR(8)   NOT NULL,
    seat_number INT          NOT NULL,
    price_cents BIGINT       NOT NULL CHECK (price_cents >= 0),
    status      VARCHAR(16)  NOT NULL DEFAULT 'AVAILABLE'
                   CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    -- Used by JPA optimistic locking. We commit with pessimistic locks,
    -- but keeping the column lets us benchmark both strategies.
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_seat_position UNIQUE (event_id, section, row_label, seat_number)
);

-- The seat map query: "all seats for this event", and the contention
-- query: "available seats for this event".
CREATE INDEX idx_seats_event_status ON seats (event_id, status);

CREATE TABLE bookings (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id),
    event_id    BIGINT      NOT NULL REFERENCES events (id),
    status      VARCHAR(16) NOT NULL
                   CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    total_cents BIGINT      NOT NULL CHECK (total_cents >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at TIMESTAMPTZ
);

CREATE INDEX idx_bookings_user ON bookings (user_id, created_at DESC);

CREATE TABLE booking_seats (
    id         BIGSERIAL PRIMARY KEY,
    booking_id BIGINT  NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    seat_id    BIGINT  NOT NULL REFERENCES seats (id),
    active     BOOLEAN NOT NULL DEFAULT TRUE
);

-- ── The double-booking backstop ───────────────────────────────
-- A partial unique index: a seat may appear in at most ONE *active*
-- booking. Cancelled bookings keep their rows (active = false) so
-- history survives. Even if every line of application logic were
-- wrong, Postgres would reject the second concurrent insert.
CREATE UNIQUE INDEX uq_seat_single_active_booking
    ON booking_seats (seat_id)
    WHERE active;

CREATE INDEX idx_booking_seats_booking ON booking_seats (booking_id);

-- Stores the outcome of a completed request so a retry with the same
-- key replays the original response instead of booking twice.
CREATE TABLE idempotency_keys (
    id             BIGSERIAL PRIMARY KEY,
    idem_key       VARCHAR(255) NOT NULL UNIQUE,
    user_id        BIGINT       NOT NULL REFERENCES users (id),
    request_hash   VARCHAR(64)  NOT NULL,
    response_status INT,
    response_body  TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_idem_expires ON idempotency_keys (expires_at);
