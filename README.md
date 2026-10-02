# TicketHub

An event ticket booking backend built around one problem: **two people clicking
the same seat at the same instant must not both end up with a ticket.**

Java 21 · Spring Boot 3.4 · PostgreSQL 16 · Redis 7 · React 18 · Docker

<!-- ![Two browsers racing for the same seat](docs/demo.gif) -->

---

## What it does

Browse events, pick seats on a live map, hold them for 120 seconds while you
check out, and confirm. Holds that lapse are swept back into the pool. Bookings
can be cancelled, which frees the seats and keeps the history.

```
AVAILABLE ──hold──> HELD ──confirm──> BOOKED
                      │                  │
                      └──expiry (120s)───┤
                                  cancel │
                      AVAILABLE <────────┘
```

## Running it

```bash
docker compose up -d        # Postgres + Redis
./mvnw spring-boot:run      # API on :8080

cd frontend
npm install && npm run dev  # UI on :5173
```

Sign in as `demo@tickethub.dev` / `password123`. A second seeded account,
`second@tickethub.dev`, uses the same password — useful for racing two browser
windows for the same seat.

API docs: **http://localhost:8080/swagger-ui.html**

```bash
curl http://localhost:8080/health
# {"status":"UP","postgres":"UP (250 seats)","redis":"UP"}
```

---

## How double booking is prevented

Three independent layers. Each is sufficient for a narrower case; together they
make the guarantee unconditional.

### 1. Atomic Redis claim, at hold time

A Lua script checks every requested seat key and sets them all, or sets none:

```lua
for i = 1, #KEYS do
    if redis.call('EXISTS', KEYS[i]) == 1 then return 0 end
end
for i = 1, #KEYS do
    redis.call('SET', KEYS[i], ARGV[1], 'EX', ARGV[2])
end
return 1
```

Redis is single-threaded and will not interleave another client's command inside
a script, so check-all-then-claim-all happens as one indivisible step. Issuing N
separate `SET NX` calls would leave a window where another request takes seat 2
between our claim of seat 1 and seat 2 — hence the script rather than a loop.

Releasing compares the value before deleting, so a hold that has already expired
cannot delete a claim a later user now owns.

### 2. Pessimistic row locks, at confirm time

`SELECT ... FOR UPDATE` on the seat rows via `SeatRepository.findAllByIdForUpdate`.
Concurrent transactions touching the same seats block rather than interleave.

Rows are locked in ascending id order. Two multi-seat bookings taking the same
pair in opposite orders would otherwise deadlock.

### 3. A partial unique index, in the database

```sql
CREATE UNIQUE INDEX uq_seat_single_active_booking
    ON booking_seats (seat_id) WHERE active;
```

A seat may appear in at most one *active* booking. Cancelled bookings keep their
rows with `active = false`, so history survives while the seat is freed.

This is the layer that makes the claim unconditional: it holds even if every
line of application logic above it is wrong. `confirm` catches the violation and
returns 409 rather than 500.

---

## Idempotency

`POST /api/bookings/confirm` accepts an `Idempotency-Key` header. A retry with
the same key replays the stored response; the same key with a different body is
rejected with 422.

The race guard is the unique index on `idem_key`, not an existence check in
Java — two concurrent retries would both pass such a check. One `INSERT` wins;
the other gets a constraint violation and is told the request is in flight.

The reservation is written in a `REQUIRES_NEW` transaction so it is visible to
other requests immediately rather than at the end of the booking transaction.
That forced a design decision worth naming: `IdempotencyStore` is a separate
bean from `IdempotencyService`, because Spring implements `@Transactional` with
a proxy, and a call between two methods of the same class never passes through
it.

---

## Tests

```bash
./mvnw test     # 23 tests
```

Integration tests run against real Postgres and Redis via Testcontainers, not H2
and an embedded fake. The behaviour under test *is* the database's — `FOR UPDATE`
semantics, a partial unique index, Redis's single-threaded script execution — so
a test against H2 would prove nothing about production.

`ConcurrentBookingTest` is the one that matters:

| Test | Assertion |
|---|---|
| 100 threads, one seat, hold only | exactly 1 succeeds |
| 100 threads, one seat, hold + confirm | exactly 1 booking; `booking_seats` has exactly 1 active row |
| 2 overlapping multi-seat requests | the loser claims nothing |

Every worker blocks on a `CountDownLatch` until all are scheduled, then all are
released at once. Without that start gate the threads finish one after another
and the test is sequential code wearing a thread pool.

`IdempotentConfirmTest` fires 20 simultaneous confirms with one key and asserts a
single booking exists.

---

## Load test

```bash
node loadtest/booking-load.mjs --concurrency 150
```

No dependencies, runs against the live app. Two separate measurements, because
they answer different questions:

- **Latency** — a bounded pool of 8 workers pulling bookings from a queue. This
  measures how long one booking takes while the system is busy, which is what a
  percentile is supposed to mean.
- **Contention** — 100 clients racing for the *same* seat. The point is not
  throughput but that exactly one wins, measured over HTTP rather than in a unit
  test.

Measured on a laptop running the app, Postgres, Redis and the load generator
together, after a 30-booking JVM warmup. Two runs landed within 10% of each
other.
 
| Measurement | Result |
|---|---|
| hold + confirm, p50 | 129 ms |
| hold + confirm, p95 | 171 ms |
| hold + confirm, p99 | 190 ms (88 samples) |
| Sustained throughput | 59 bookings/sec, 8 concurrent workers |
| 100 concurrent holds on one seat | 1 winner, 99 × 409, drained in 597 ms |
 
An earlier version of this script fired every request simultaneously and
reported a p99 of 2036 ms with p50 at 1871 ms. Mean, median and p99 within 10%
of each other is not a latency distribution — it is a queue. That run measured
how long the server took to drain a burst, on a cold JVM still running
interpreted. Warming up and bounding concurrency to 8 workers gave the numbers
above, 11× lower.
 
---
 
## Auth

JWT bearer tokens, HS256. `/api/auth/register` and `/api/auth/login` are public;
everything under `/api/bookings` requires a token. Browsing events stays public.

The user id comes from the authenticated principal, never from the request body.
An earlier version took `userId` as a field, which meant any client could book as
anyone.

Passwords are BCrypt at cost 10. Login returns the same error for an unknown
email as for a wrong password, so the endpoint cannot be used to enumerate
accounts.

---

## Front-end

React 18 + Vite in `frontend/`. Vite proxies `/api` to `:8080`, so the browser
sees same-origin requests and CORS never enters the picture in development.

**The seat map polls every 3 seconds.** Open two browser windows, hold a seat in
one, and watch it turn amber in the other. That is the defence made visible.
Polling rather than websockets is a deliberate trade — a few lines versus a push
channel — and a hot event at real scale would want SSE or websockets so thousands
of clients are not polling one endpoint.

**The idempotency key is generated once per checkout and held in a `useRef`.**
If the response is lost and the user clicks again, the server replays the
original booking. A fresh key per click would make the mechanism decorative.

The client-side route guard is a convenience, not a security control — every
protected endpoint is enforced by the JWT filter server-side.

---

## Schema

Flyway owns the schema (`src/main/resources/db/migration`); Hibernate runs with
`ddl-auto: validate` and never alters tables. `ddl-auto: update` is fine for a
toy and unusable in production: you cannot review a migration that does not
exist as an artifact, and the resulting schema depends on entity load order.

```
users ──< bookings ──< booking_seats >── seats >── events
                                             idempotency_keys
```

---

## Not built, and why

**Payment.** The hold window *is* the payment window. In production, confirm
would split: hold the seats, create a payment intent, write the booking only once
the gateway confirms. That changes the design in three ways — you cannot wrap a
database transaction around an external HTTP call, so it needs an outbox or a
saga with compensation; the idempotency key stops being a nice-to-have because a
retried charge is money rather than a duplicate row; and the hold TTL has to
exceed the gateway's own timeout. Scoped out deliberately rather than faked with
a button that does nothing. The UI is honest about it: seats are reserved and
paid for at the venue.

**Email verification.** Registration accepts any syntactically valid address and
sends nothing, so an account can be created as anyone. A real system sends a
confirmation link and leaves the account unverified until it is clicked.

**Refresh tokens and revocation.** Tokens live 60 minutes and cannot be revoked
before they expire — the standard problem with stateless JWTs. Fixing it needs a
short-lived access token plus a refresh token, or a denylist, which reintroduces
the server-side state JWTs were chosen to avoid.

**Horizontal scaling.** The expiry sweeper is a single-instance `@Scheduled` job.
Several app instances would run it concurrently; that needs a shared lock such as
ShedLock.

**Seat selection beyond one event.** Seats are a flat grid with a single pricing
tier per row. Real venues have sections, accessibility seating and dynamic
pricing.

---

## Build log

| Day | Work |
|---|---|
| 1 | Scaffold, Docker Compose, Flyway schema, JPA entities, health check |
| 2 | Event listing, seat map, hold → confirm → cancel (deliberately not concurrency-safe) |
| 3 | Redis atomic claims, pessimistic locking, idempotency keys |
| 4 | JWT auth, Testcontainers concurrency suite |
| 5 | React front-end with live seat map |
| 6 | Load test, OpenAPI docs, documentation |

Day 2's race is still in the git history. The fix in Day 3 reads as a diff
rather than appearing fully formed.
