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

Check it came up:

```bash
curl http://localhost:8080/health
# {"status":"UP","postgres":"UP (250 seats)","redis":"UP"}
```

## Schema notes

Flyway owns the schema (`src/main/resources/db/migration`). Hibernate runs with
`ddl-auto: validate`, so it verifies the entities match but never alters tables.

The double-booking invariant is enforced at three levels:

1. **Redis holds** — a short-lived reservation so the UI can show a seat as taken
   before payment completes (Day 3).
2. **Pessimistic row locks** — `SELECT ... FOR UPDATE` on the seat rows at confirm
   time, so concurrent transactions serialise (Day 3).
3. **A partial unique index** — `uq_seat_single_active_booking` allows a seat to
   appear in at most one active booking. If the first two layers ever failed,
   Postgres would still reject the second insert.

## Progress

- [x] Day 1 — scaffold, Docker, schema, entities, health check
- [ ] Day 2 — event/seat listing, hold → confirm → cancel (single-threaded)
- [ ] Day 3 — Redis holds, pessimistic locking, idempotency keys
- [ ] Day 4 — JWT auth, concurrency test
- [ ] Day 5 — React front-end
- [ ] Day 6 — load test, demo recording, docs
