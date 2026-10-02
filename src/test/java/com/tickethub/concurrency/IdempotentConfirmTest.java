package com.tickethub.concurrency;

import com.tickethub.domain.Seat;
import com.tickethub.domain.SeatStatus;
import com.tickethub.repository.*;
import com.tickethub.service.BookingService;
import com.tickethub.service.IdempotencyService;
import com.tickethub.support.IntegrationTestBase;
import com.tickethub.web.dto.BookingResponse;
import com.tickethub.web.dto.ConfirmRequest;
import com.tickethub.web.dto.HoldRequest;
import com.tickethub.web.dto.HoldResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrying a confirm must not produce a second booking.
 *
 * The scenario being defended against: the server commits the booking,
 * then the connection drops before the response arrives. The client has
 * no way to know whether it worked, so it retries.
 */
class IdempotentConfirmTest extends IntegrationTestBase {

    @Autowired BookingService bookingService;
    @Autowired IdempotencyService idempotencyService;
    @Autowired SeatRepository seatRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingSeatRepository bookingSeatRepository;
    @Autowired IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate redis;

    private Long seatId;
    private Long eventId;
    private Long userId;

    @BeforeEach
    void reset() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        idempotencyKeyRepository.deleteAll();
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
    @DisplayName("a sequential retry replays the first response")
    void retryReplaysInsteadOfRebooking() {
        HoldResponse hold = bookingService.hold(new HoldRequest(eventId, List.of(seatId)), userId);
        ConfirmRequest request = new ConfirmRequest(hold.holdId());
        String key = UUID.randomUUID().toString();

        BookingResponse first = idempotencyService.execute(
                key, userId, request, BookingResponse.class,
                () -> bookingService.confirm(request, userId));

        BookingResponse second = idempotencyService.execute(
                key, userId, request, BookingResponse.class,
                () -> bookingService.confirm(request, userId));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(bookingRepository.findAll()).hasSize(1);
        assertThat(bookingSeatRepository.findBySeatIdAndActiveTrue(seatId)).hasSize(1);
    }

    @Test
    @DisplayName("20 simultaneous retries with one key still produce one booking")
    void concurrentRetriesProduceOneBooking() throws Exception {
        HoldResponse hold = bookingService.hold(new HoldRequest(eventId, List.of(seatId)), userId);
        ConfirmRequest request = new ConfirmRequest(hold.holdId());
        String key = UUID.randomUUID().toString();

        int attempts = 20;
        AtomicInteger succeeded = new AtomicInteger();
        AtomicReference<Long> bookingId = new AtomicReference<>();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    BookingResponse response = idempotencyService.execute(
                            key, userId, request, BookingResponse.class,
                            () -> bookingService.confirm(request, userId));
                    bookingId.compareAndSet(null, response.id());
                    succeeded.incrementAndGet();
                } catch (Exception ignored) {
                    // 409 REQUEST_IN_PROGRESS while the first attempt runs.
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(doneGate.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // However many callers got a response, there is one booking.
        assertThat(bookingRepository.findAll())
                .as("one idempotency key, one booking, regardless of retry count")
                .hasSize(1);
        assertThat(bookingSeatRepository.findBySeatIdAndActiveTrue(seatId)).hasSize(1);
        assertThat(succeeded.get()).isGreaterThanOrEqualTo(1);
    }
}
