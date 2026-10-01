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

## Current limitation — read this before Day 3

`BookingService.hold()` is **not concurrency-safe, on purpose.** It reads each
seat's status and writes `HELD` in a separate step. Two requests can both read
`AVAILABLE` before either writes, and both succeed — the classic check-then-act
race, which here means a double booking.

Holds also live in a `ConcurrentHashMap` in the JVM heap, so they die with the
process and are invisible to a second app instance.

Day 3 fixes both:

- Redis holds — atomic claim via `SET NX`, with a TTL, shared across instances
- `SELECT ... FOR UPDATE` on the seat rows at confirm time
- Idempotency keys so a retried request replays rather than re-books

A partial unique index (`uq_seat_single_active_booking`) already backstops all
of it: a seat may appear in at most one booking row where `active = true`, so
even if every line of application logic were wrong, Postgres would reject the
second insert.

## Schema

Flyway owns the schema (`src/main/resources/db/migration`); Hibernate runs with
`ddl-auto: validate` and never alters tables.

## Progress

- [x] Day 1 — scaffold, Docker, schema, entities, health check
- [x] Day 2 — event/seat listing, hold → confirm → cancel, expiry sweeper
- [ ] Day 3 — Redis holds, pessimistic locking, idempotency keys
- [ ] Day 4 — JWT auth, concurrency test
- [ ] Day 5 — React front-end
- [ ] Day 6 — load test, demo recording, docs
