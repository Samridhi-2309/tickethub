package com.tickethub.service;

import com.tickethub.domain.*;
import com.tickethub.repository.*;
import com.tickethub.service.hold.HoldStore;
import com.tickethub.service.hold.SeatHold;
import com.tickethub.web.dto.*;
import com.tickethub.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The booking flow: hold -> confirm, with cancel as the reverse.
 *
 * ── DAY 2 WARNING ────────────────────────────────────────────────
 * This version is NOT concurrency-safe, on purpose. `hold` reads each
 * seat's status and then writes it in a separate step, so two requests
 * can both read AVAILABLE before either writes HELD, and both proceed.
 * That check-then-act gap is the double-booking bug.
 *
 * Day 3 closes it with Redis holds and SELECT ... FOR UPDATE at confirm
 * time. Keeping the naive version in git history means the fix shows up
 * as a readable diff rather than appearing fully formed.
 * ─────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BookingService {

    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final UserRepository userRepository;
    private final HoldStore holdStore;

    @Value("${tickethub.hold-seconds:120}")
    private long holdSeconds;

    @Value("${tickethub.max-seats-per-booking:8}")
    private int maxSeatsPerBooking;

    // ── Hold ─────────────────────────────────────────────────────

    @Transactional
    public HoldResponse hold(HoldRequest request) {
        if (request.seatIds().size() > maxSeatsPerBooking) {
            throw ApiException.badRequest(
                    "Cannot hold more than " + maxSeatsPerBooking + " seats in one booking");
        }
        if (!userRepository.existsById(request.userId())) {
            throw ApiException.notFound("No user with id " + request.userId());
        }

        List<Long> seatIds = request.seatIds().stream().distinct().sorted().toList();
        List<Seat> seats = seatRepository.findAllById(seatIds);

        if (seats.size() != seatIds.size()) {
            throw ApiException.notFound("One or more seat ids do not exist");
        }

        long totalCents = 0;
        for (Seat seat : seats) {
            if (!seat.getEventId().equals(request.eventId())) {
                throw ApiException.badRequest(
                        "Seat " + seat.getId() + " does not belong to event " + request.eventId());
            }
            // THE RACE: another request can pass this same check before we
            // write below. Day 3 replaces it with an atomic Redis claim.
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                throw ApiException.conflict(
                        "Seat " + seat.getId() + " is " + seat.getStatus().name().toLowerCase());
            }
            totalCents += seat.getPriceCents();
        }

        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.HELD);
        }
        seatRepository.saveAll(seats);

        Instant expiresAt = Instant.now().plus(Duration.ofSeconds(holdSeconds));
        SeatHold heldSeats = new SeatHold(
                UUID.randomUUID().toString(),
                request.userId(),
                request.eventId(),
                seatIds,
                totalCents,
                expiresAt);
        holdStore.put(heldSeats);

        log.info("Hold {} created for user {} on seats {}",
                heldSeats.holdId(), request.userId(), seatIds);

        return new HoldResponse(
                heldSeats.holdId(),
                heldSeats.eventId(),
                heldSeats.seatIds(),
                heldSeats.totalCents(),
                expiresAt,
                holdSeconds);
    }

    // ── Confirm ──────────────────────────────────────────────────

    @Transactional
    public BookingResponse confirm(ConfirmRequest request) {
        SeatHold hold = holdStore.get(request.holdId())
                .orElseThrow(() -> ApiException.notFound("No hold with id " + request.holdId()));

        if (!hold.userId().equals(request.userId())) {
            throw ApiException.badRequest("Hold belongs to a different user");
        }
        if (hold.isExpired(Instant.now())) {
            throw ApiException.holdExpired("Hold " + request.holdId() + " has expired");
        }

        List<Seat> seats = seatRepository.findAllById(hold.seatIds());
        for (Seat seat : seats) {
            if (seat.getStatus() != SeatStatus.HELD) {
                throw ApiException.conflict(
                        "Seat " + seat.getId() + " is no longer held — it is "
                                + seat.getStatus().name().toLowerCase());
            }
        }

        Booking booking = bookingRepository.save(Booking.builder()
                .userId(hold.userId())
                .eventId(hold.eventId())
                .status(BookingStatus.CONFIRMED)
                .totalCents(hold.totalCents())
                .build());

        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.BOOKED);
            bookingSeatRepository.save(BookingSeat.builder()
                    .bookingId(booking.getId())
                    .seatId(seat.getId())
                    .active(true)
                    .build());
        }
        seatRepository.saveAll(seats);
        holdStore.remove(hold.holdId());

        log.info("Booking {} confirmed for user {} on seats {}",
                booking.getId(), hold.userId(), hold.seatIds());

        return toBookingResponse(booking, hold.seatIds());
    }

    // ── Cancel ───────────────────────────────────────────────────

    @Transactional
    public BookingResponse cancel(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> ApiException.notFound("No booking with id " + bookingId));

        if (!booking.getUserId().equals(userId)) {
            throw ApiException.badRequest("Booking belongs to a different user");
        }
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw ApiException.badRequest("Booking " + bookingId + " is already cancelled");
        }

        List<BookingSeat> links = bookingSeatRepository.findByBookingId(bookingId);
        List<Long> seatIds = links.stream().map(BookingSeat::getSeatId).toList();

        // active = false rather than deleting the row: the partial unique
        // index frees the seat for a new booking while the history stays.
        for (BookingSeat link : links) {
            link.setActive(false);
        }
        bookingSeatRepository.saveAll(links);

        List<Seat> seats = seatRepository.findAllById(seatIds);
        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.AVAILABLE);
        }
        seatRepository.saveAll(seats);

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(Instant.now());
        bookingRepository.save(booking);

        log.info("Booking {} cancelled, seats {} released", bookingId, seatIds);

        return toBookingResponse(booking, seatIds);
    }

    // ── Reads ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BookingResponse getBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> ApiException.notFound("No booking with id " + bookingId));
        List<Long> seatIds = bookingSeatRepository.findByBookingId(bookingId).stream()
                .map(BookingSeat::getSeatId)
                .toList();
        return toBookingResponse(booking, seatIds);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> listBookings(Long userId) {
        return bookingRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(b -> toBookingResponse(
                        b,
                        bookingSeatRepository.findByBookingId(b.getId()).stream()
                                .map(BookingSeat::getSeatId)
                                .toList()))
                .toList();
    }

    // ── Expiry ───────────────────────────────────────────────────

    /**
     * Releases seats whose hold lapsed without a confirm.
     *
     * Still needed after Day 3's Redis migration: Redis expiring a key
     * does not reset seat status in Postgres, so something has to put
     * those rows back to AVAILABLE.
     */
    @Transactional
    public int releaseExpiredHolds() {
        List<SeatHold> expired = holdStore.findExpired();
        int released = 0;

        for (SeatHold hold : expired) {
            List<Seat> seats = seatRepository.findAllById(hold.seatIds());
            for (Seat seat : seats) {
                // Only roll back seats still HELD. A seat that reached
                // BOOKED was confirmed in the gap and must be left alone.
                if (seat.getStatus() == SeatStatus.HELD) {
                    seat.setStatus(SeatStatus.AVAILABLE);
                    released++;
                }
            }
            seatRepository.saveAll(seats);
            holdStore.remove(hold.holdId());
            log.info("Hold {} expired, released seats {}", hold.holdId(), hold.seatIds());
        }
        return released;
    }

    private BookingResponse toBookingResponse(Booking booking, List<Long> seatIds) {
        return new BookingResponse(
                booking.getId(),
                booking.getUserId(),
                booking.getEventId(),
                booking.getStatus(),
                booking.getTotalCents(),
                seatIds,
                booking.getCreatedAt(),
                booking.getCancelledAt());
    }
}
