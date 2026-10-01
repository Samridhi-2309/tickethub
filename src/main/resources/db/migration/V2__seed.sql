-- ─────────────────────────────────────────────────────────────
-- V2: seed data for local development and load testing
-- One event with a 10 x 20 grid = 200 seats.
-- ─────────────────────────────────────────────────────────────

INSERT INTO users (email, password_hash, display_name)
VALUES
    -- placeholder hashes; replaced on Day 4 when real auth lands
    ('demo@tickethub.dev',  'CHANGE_ME', 'Demo User'),
    ('second@tickethub.dev','CHANGE_ME', 'Second User');

INSERT INTO events (name, venue, starts_at)
VALUES
    ('Coldplay — Music of the Spheres', 'JN Stadium, Delhi', now() + interval '30 days'),
    ('Local Indie Night',               'Bluenote, Bengaluru', now() + interval '7 days');

-- 200 seats for event 1: rows 1..10, seats 1..20
INSERT INTO seats (event_id, section, row_label, seat_number, price_cents)
SELECT
    1,
    'A',
    r::text,
    s,
    CASE WHEN r <= 3 THEN 500000 ELSE 250000 END
FROM generate_series(1, 10) AS r
CROSS JOIN generate_series(1, 20) AS s;

-- 50 seats for event 2
INSERT INTO seats (event_id, section, row_label, seat_number, price_cents)
SELECT 2, 'GA', r::text, s, 80000
FROM generate_series(1, 5) AS r
CROSS JOIN generate_series(1, 10) AS s;
