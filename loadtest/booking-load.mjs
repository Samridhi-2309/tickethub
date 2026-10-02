#!/usr/bin/env node
/**
 * TicketHub load test — no dependencies, runs on Node 18+.
 *
 *   node loadtest/booking-load.mjs
 *   node loadtest/booking-load.mjs --concurrency 50 --contention 100
 *
 * Two separate measurements, because they answer different questions:
 *
 *   THROUGHPUT — N users each booking a DIFFERENT seat. Nothing
 *   contends, so this measures the cost of the booking path itself:
 *   JWT verification, the Redis round trip, two Postgres transactions,
 *   JSON serialisation. This is where p99 comes from.
 *
 *   CONTENTION — N users all racing for the SAME seat. Throughput is
 *   not the point; the point is that exactly one wins. This is the
 *   correctness claim, measured over HTTP rather than in a unit test.
 *
 * Reporting a single blended number across both would be meaningless,
 * which is why they are kept apart.
 */

const args = process.argv.slice(2);
const flag = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i === -1 ? fallback : Number(args[i + 1]);
};

const BASE = process.env.TICKETHUB_URL ?? 'http://localhost:8080';
const EMAIL = process.env.TICKETHUB_EMAIL ?? 'demo@tickethub.dev';
const PASSWORD = process.env.TICKETHUB_PASSWORD ?? 'password123';

const CONCURRENCY = flag('concurrency', 50);
const CONTENTION = flag('contention', 100);
const EVENT_ID = flag('event', 1);

// ── helpers ──────────────────────────────────────────────────

const percentile = (sorted, p) => {
  if (sorted.length === 0) return 0;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[idx];
};

function summarise(label, samples, wallMs) {
  const sorted = [...samples].sort((a, b) => a - b);
  const mean = samples.reduce((a, b) => a + b, 0) / (samples.length || 1);
  console.log(`\n  ${label}`);
  console.log(`    samples      ${samples.length}`);
  console.log(`    mean         ${mean.toFixed(1)} ms`);
  console.log(`    p50          ${percentile(sorted, 50).toFixed(1)} ms`);
  console.log(`    p95          ${percentile(sorted, 95).toFixed(1)} ms`);
  console.log(`    p99          ${percentile(sorted, 99).toFixed(1)} ms`);
  console.log(`    max          ${(sorted.at(-1) ?? 0).toFixed(1)} ms`);
  if (wallMs) {
    console.log(`    throughput   ${(samples.length / (wallMs / 1000)).toFixed(1)} req/s`);
  }
}

async function post(path, body, token, idemKey) {
  return fetch(BASE + path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(idemKey ? { 'Idempotency-Key': idemKey } : {})
    },
    body: JSON.stringify(body)
  });
}

// ── setup ────────────────────────────────────────────────────

async function login() {
  const res = await post('/api/auth/login', { email: EMAIL, password: PASSWORD });
  if (!res.ok) throw new Error(`Login failed (${res.status}). Is the backend running?`);
  return (await res.json()).token;
}

async function availableSeats() {
  const res = await fetch(`${BASE}/api/events/${EVENT_ID}/seats`);
  if (!res.ok) throw new Error(`Seat map failed (${res.status})`);
  const map = await res.json();
  return map.seats.filter((s) => s.status === 'AVAILABLE').map((s) => s.id);
}

/**
 * Releases all workers at the same instant.
 *
 * Without this, each worker starts as the previous one finishes and the
 * "concurrent" test is a sequential one. Same reason the JUnit
 * concurrency test uses a CountDownLatch.
 */
function startGate() {
  let open;
  const gate = new Promise((resolve) => { open = resolve; });
  return { gate, open: () => open() };
}

// ── warmup ───────────────────────────────────────────────────

/**
 * The JVM runs interpreted until the JIT decides a method is hot, so
 * the first few hundred requests are several times slower than steady
 * state. Measuring without warming up measures the compiler, not the
 * booking path.
 */
async function warmup(token, seatIds) {
  process.stdout.write('  warming up');
  for (const seatId of seatIds.slice(0, 30)) {
    const r = await post('/api/bookings/hold', { eventId: EVENT_ID, seatIds: [seatId] }, token);
    if (r.ok) {
      const hold = await r.json();
      await post('/api/bookings/confirm', { holdId: hold.holdId }, token, crypto.randomUUID());
    }
    process.stdout.write('.');
  }
  console.log(' done\n');
}

// ── latency: steady state, bounded concurrency ──────────────

/**
 * A fixed small number of workers pulling from a queue, rather than
 * firing everything at once. This measures how long ONE booking takes
 * while the system is busy — which is what p99 is supposed to mean.
 * Issuing N requests simultaneously instead measures how long it takes
 * to drain a burst of N, and produces a suspiciously flat distribution
 * where p50 and p99 are the same number.
 */
async function latencyTest(token, seatIds, workers = 8) {
  const queue = [...seatIds];
  const latencies = [];
  let failed = 0;

  async function worker() {
    while (queue.length) {
      const seatId = queue.shift();
      const started = performance.now();
      try {
        const holdRes = await post('/api/bookings/hold',
          { eventId: EVENT_ID, seatIds: [seatId] }, token);
        if (!holdRes.ok) { failed++; continue; }
        const hold = await holdRes.json();
        const confirmRes = await post('/api/bookings/confirm',
          { holdId: hold.holdId }, token, crypto.randomUUID());
        if (!confirmRes.ok) { failed++; continue; }
        latencies.push(performance.now() - started);
      } catch { failed++; }
    }
  }

  const wallStart = performance.now();
  await Promise.all(Array.from({ length: workers }, worker));
  const wallMs = performance.now() - wallStart;

  console.log(`\n── Latency: ${workers} concurrent workers, ${latencies.length} bookings ──`);
  console.log(`    failed       ${failed}`);
  summarise('hold + confirm, end to end', latencies, wallMs);
}

// ── contention: everyone wants one seat ─────────────────────

async function contentionTest(token, seatId) {
  const { gate, open } = startGate();
  let won = 0, rejected = 0, errored = 0;

  const workers = Array.from({ length: CONTENTION }, () => (async () => {
    await gate;
    try {
      const res = await post('/api/bookings/hold',
        { eventId: EVENT_ID, seatIds: [seatId] }, token);
      if (res.ok) won++;
      else if (res.status === 409) rejected++;
      else errored++;
    } catch { errored++; }
  })());

  const wallStart = performance.now();
  open();
  await Promise.all(workers);
  const wallMs = performance.now() - wallStart;

  console.log(`\n── Contention: ${CONTENTION} concurrent holds, ONE seat (#${seatId}) ──`);
  console.log(`    won          ${won}      <- must be exactly 1`);
  console.log(`    rejected 409 ${rejected}`);
  console.log(`    errored      ${errored}`);
  console.log(`    drained in   ${wallMs.toFixed(0)} ms`);
  // No percentiles here on purpose: every request is issued at the same
  // instant, so per-request timings measure queue position, not service
  // time. The assertion is the single winner.

  if (won !== 1) {
    console.log(`\n  FAIL: ${won} winners. The claim is not exclusive.`);
    process.exitCode = 1;
  } else {
    console.log('\n  PASS: exactly one winner.');
  }
}

// ── run ──────────────────────────────────────────────────────

(async () => {
  console.log(`TicketHub load test → ${BASE}\n`);

  const token = await login();
  let seats = await availableSeats();
  console.log(`  ${seats.length} seats available on event ${EVENT_ID}`);

  if (seats.length < 80) {
    console.log('\n  Need ~80 free seats. Reset with:');
    console.log('    docker exec -it tickethub-postgres psql -U tickethub -d tickethub \\');
    console.log('      -c "DELETE FROM booking_seats; DELETE FROM bookings; UPDATE seats SET status=\'AVAILABLE\';"');
    console.log('    docker exec -it tickethub-redis redis-cli FLUSHALL');
    process.exit(1);
  }

  await warmup(token, seats);

  seats = await availableSeats();
  await contentionTest(token, seats[0]);

  seats = await availableSeats();
  await latencyTest(token, seats.slice(0, CONCURRENCY));

  console.log('\nSeats are now booked. Reset before re-running:');
  console.log('  docker exec -it tickethub-postgres psql -U tickethub -d tickethub \\');
  console.log('    -c "DELETE FROM booking_seats; DELETE FROM bookings; UPDATE seats SET status=\'AVAILABLE\';"');
  console.log('  docker exec -it tickethub-redis redis-cli FLUSHALL\n');
})();