package com.tickethub.concurrency;

import com.tickethub.domain.BookingStatus;
import com.tickethub.domain.Seat;
import com.tickethub.domain.SeatStatus;
import com.tickethub.repository.*;
import com.tickethub.service.BookingService;
import com.tickethub.support.IntegrationTestBase;
import com.tickethub.web.dto.ConfirmRequest;
import com.tickethub.web.dto.HoldRequest;
import com.tickethub.web.dto.HoldResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test this project exists to pass.
 *
 * ── Why it is written this way ───────────────────────────────────
 * Firing N requests in a loop proves nothing: each finishes before the
 * next starts, so they never contend. To create a real race, every
 * thread must be parked at the same instant and released together.
 *
 *   startGate  — every worker blocks on it after being scheduled, so
 *                thread creation and warm-up are NOT part of the race
 *   doneGate   — the main thread waits for all workers to finish
 *
 * Without the start gate this is a sequential test wearing a thread pool.
 * ─────────────────────────────────────────────────────────────────
 */
class ConcurrentBookingTest extends IntegrationTestBase {

    private static final int CONCURRENT_USERS = 100;

    @Autowired BookingService bookingService;
    @Autowired SeatRepository seatRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingSeatRepository bookingSeatRepository;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate redis;

    private Long seatId;
    private Long eventId;
    private Long userId;

    @BeforeEach
    void reset() {
        // Wipe Redis so a claim left by an earlier test cannot leak in.
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        bookingSeatRepository.deleteAll();
        bookingRepository.deleteAll();

        List<Seat> seats = seatRepository.findAll();
        seats.forEach(s -> s.setStatus(SeatStatus.AVAILABLE));
        seatRepository.saveAll(seats);

        Seat target = seats.get(0);
        seatId = target.getId();
        eventId = target.getEventId();
        userId = userRepository.findAll().get(0).getId();
    }

    @Test
    @DisplayName("100 threads racing for one seat: exactly one hold succeeds")
    void onlyOneHoldWinsTheSeat() throws Exception {
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(CONCURRENT_USERS);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_USERS);

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    bookingService.hold(new HoldRequest(eventId, List.of(seatId)), userId);
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();                       // release them all at once
        assertThat(doneGate.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(succeeded.get())
                .as("exactly one of %d concurrent holds may win the seat", CONCURRENT_USERS)
                .isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(CONCURRENT_USERS - 1);

        Seat seat = seatRepository.findById(seatId).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
    }

    @Test
    @DisplayName("100 threads racing hold+confirm: exactly one booking exists")
    void onlyOneBookingIsCreated() throws Exception {
        AtomicInteger booked = new AtomicInteger();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(CONCURRENT_USERS);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_USERS);

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    HoldResponse hold =
                            bookingService.hold(new HoldRequest(eventId, List.of(seatId)), userId);
                    bookingService.confirm(new ConfirmRequest(hold.holdId()), userId);
                    booked.incrementAndGet();
                } catch (Exception e) {
                    // Losing the race is the expected outcome for 99 of them.
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(doneGate.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(booked.get()).isEqualTo(1);

        // The claim that actually matters: one active row for this seat.
        // This is what the partial unique index guarantees, and checking
        // it directly is stronger than trusting the service's own count.
        assertThat(bookingSeatRepository.findBySeatIdAndActiveTrue(seatId))
                .as("a seat may belong to at most one active booking")
                .hasSize(1);

        assertThat(bookingRepository.findAll())
                .filteredOn(b -> b.getStatus() == BookingStatus.CONFIRMED)
                .hasSize(1);

        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.BOOKED);
    }

    @Test
    @DisplayName("overlapping multi-seat requests never half-claim")
    void multiSeatClaimIsAllOrNothing() throws Exception {
        List<Seat> seats = seatRepository.findAll().stream()
                .filter(s -> s.getEventId().equals(eventId))
                .limit(4)
                .toList();

        Long a = seats.get(0).getId();
        Long b = seats.get(1).getId();
        Long c = seats.get(2).getId();

        // Thread 1 wants [a, b], thread 2 wants [b, c]. They overlap on b,
        // so exactly one may win — and the loser must leave its
        // non-contended seat untouched.
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger wins = new AtomicInteger();

        for (List<Long> request : List.of(List.of(a, b), List.of(b, c))) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    bookingService.hold(new HoldRequest(eventId, request), userId);
                    wins.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(doneGate.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(wins.get()).isEqualTo(1);

        long heldCount = List.of(a, b, c).stream()
                .map(id -> seatRepository.findById(id).orElseThrow())
                .filter(s -> s.getStatus() == SeatStatus.HELD)
                .count();

        assertThat(heldCount)
                .as("the winner took exactly its two seats; the loser claimed nothing")
                .isEqualTo(2);
    }
}
