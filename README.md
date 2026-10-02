# TicketHub

Event ticket booking backend. The engineering problem it exists to solve is
**preventing double booking under concurrent load** — two people clicking the
same seat at the same instant must not both end up with a ticket.

## Stack

Java 21 · Spring Boot 3.4 · PostgreSQL 16 · Redis 7 · Flyway · Docker Compose

## Running it

```bash
docker compose up -d          # Postgres + Redis
./mvnw spring-boot:run        # app on :8080
```

```bash
curl http://localhost:8080/health
# {"status":"UP","postgres":"UP (250 seats)","redis":"UP"}
```

## API

| Method | Path | Purpose |
|---|---|---|
| `GET`  | `/health` | App + Postgres + Redis liveness |
| `GET`  | `/api/events` | All events with seat counts |
| `GET`  | `/api/events/{id}` | One event |
| `GET`  | `/api/events/{id}/seats` | Full seat map |
| `POST` | `/api/bookings/hold` | Claim seats for a short window |
| `POST` | `/api/bookings/confirm` | Turn a live hold into a booking |
| `POST` | `/api/bookings/{id}/cancel?userId=` | Cancel and release seats |
| `GET`  | `/api/bookings/{id}` | One booking |
| `GET`  | `/api/bookings?userId=` | A user's bookings |

`api-tests.http` has a runnable walkthrough including the error cases.

## The booking flow

```
AVAILABLE ──hold──> HELD ──confirm──> BOOKED
                      │                  │
                      └──expiry (120s)───┤
                                  cancel │
                      AVAILABLE <────────┘
```

A hold is a short-lived claim so the UI can show a seat as taken while the user
checks out, without committing a booking. Holds lapse after 120 seconds; a
sweeper runs every 5 seconds and returns lapsed seats to `AVAILABLE`.

## How double booking is prevented

Three independent layers, each sufficient on its own for a narrower case:

**1. Atomic Redis claim (`hold`)**
A Lua script checks every requested seat key and sets them all, or sets none.
Redis is single-threaded and will not interleave another client's command
inside a script, so the check-all-then-claim-all sequence cannot be raced.
Issuing N separate `SET NX` calls would leave a window between seat 1 and
seat 2 — hence the script rather than a loop.

**2. Pessimistic row locks (`confirm`)**
`SELECT ... FOR UPDATE` on the seat rows, via
`SeatRepository.findAllByIdForUpdate`. Concurrent transactions touching the
same seats block rather than interleave. Rows are locked in ascending id
order so two multi-seat bookings cannot deadlock by taking the same pair in
opposite orders.

**3. A partial unique index (the database)**
`uq_seat_single_active_booking` allows a seat in at most one `booking_seats`
row where `active = true`. This is what makes the guarantee unconditional —
it holds even if layers 1 and 2 are both wrong. `confirm` catches the
violation and returns 409 rather than 500.

## Idempotency

`POST /api/bookings/confirm` accepts an `Idempotency-Key` header. A retry
with the same key replays the stored response instead of creating a second
booking; the same key with a different body is rejected with 422.

The race guard is the unique index on `idem_key`, not an existence check in
Java — two concurrent retries would both pass such a check. One `INSERT`
wins; the other gets a constraint violation and is told the request is in
flight.

## Why the sweeper still exists

Redis expires its own keys, but nothing in Redis can update a Postgres row.
A seat would sit at `HELD` forever once its claim lapsed. So the sweep runs
from the database side: find `HELD` rows, ask Redis which are still claimed,
release the rest.

## Schema

Flyway owns the schema (`src/main/resources/db/migration`); Hibernate runs with
`ddl-auto: validate` and never alters tables.

## Progress

- [x] Day 1 — scaffold, Docker, schema, entities, health check
- [x] Day 2 — event/seat listing, hold → confirm → cancel, expiry sweeper
- [x] Day 3 — Redis holds, pessimistic locking, idempotency keys
- [ ] Day 4 — JWT auth, concurrency test
- [ ] Day 5 — React front-end
- [ ] Day 6 — load test, demo recording, docs
