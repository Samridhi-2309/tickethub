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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The booking flow: hold -> confirm, with cancel as the reverse.
 *
 * ── What changed on Day 3 ────────────────────────────────────────
 * Day 2's hold() read each seat's status and then wrote HELD in a
 * separate step. Two requests could both read AVAILABLE before either
 * wrote — a check-then-act race, and a double booking.
 *
 * There are now three independent defences:
 *
 *   1. hold()    — one atomic Redis claim across every seat. Redis runs
 *                  the Lua script single-threaded, so exactly one caller
 *                  can win a given seat. No window to race in.
 *
 *   2. confirm() — SELECT ... FOR UPDATE on the seat rows, so concurrent
 *                  transactions touching the same seats serialise at the
 *                  database rather than interleaving.
 *
 *   3. the DB    — a partial unique index (uq_seat_single_active_booking)
 *                  permits a seat in at most one active booking. If 1 and
 *                  2 both failed, the second INSERT still cannot commit.
 *
 * Layer 3 is the one that makes the guarantee unconditional: it holds
 * even if the application logic is wrong.
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
    public HoldResponse hold(HoldRequest request, Long userId) {
        if (request.seatIds().size() > maxSeatsPerBooking) {
            throw ApiException.badRequest(
                    "Cannot hold more than " + maxSeatsPerBooking + " seats in one booking");
        }
        if (!userRepository.existsById(userId)) {
            throw ApiException.notFound("No user with id " + userId);
        }

        // Sorted so that multi-seat bookings always touch rows in the same
        // order. Two transactions grabbing seats 5 and 9 in opposite
        // orders would deadlock; a consistent order makes that impossible.
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
            // A seat already BOOKED is gone for good, so reject early.
            // HELD is NOT checked here — the Redis claim below decides
            // that, atomically. Checking it in Java would reintroduce the
            // exact race this method was rewritten to remove.
            if (seat.getStatus() == SeatStatus.BOOKED) {
                throw ApiException.conflict("Seat " + seat.getId() + " is already booked");
            }
            totalCents += seat.getPriceCents();
        }

        Instant expiresAt = Instant.now().plus(Duration.ofSeconds(holdSeconds));
        SeatHold hold = new SeatHold(
                UUID.randomUUID().toString(),
                userId,
                request.eventId(),
                seatIds,
                totalCents,
                expiresAt);

        // THE GATE. All-or-nothing across every seat, decided by Redis.
        if (!holdStore.tryClaim(hold)) {
            List<Long> taken = holdStore.claimedSeats(seatIds);
            throw ApiException.conflict("Seat(s) " + taken + " are being booked by someone else");
        }

        // Only now does Postgres get told. The claim already guarantees
        // we are the sole writer for these rows.
        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.HELD);
        }
        seatRepository.saveAll(seats);

        log.info("Hold {} claimed seats {} for user {}",
                hold.holdId(), seatIds, userId);

        return new HoldResponse(
                hold.holdId(),
                hold.eventId(),
                hold.seatIds(),
                hold.totalCents(),
                expiresAt,
                holdSeconds);
    }

    // ── Confirm ──────────────────────────────────────────────────

    @Transactional
    public BookingResponse confirm(ConfirmRequest request, Long userId) {
        SeatHold hold = holdStore.get(request.holdId())
                .orElseThrow(() -> ApiException.holdExpired(
                        "Hold " + request.holdId() + " has expired or does not exist"));

        if (!hold.userId().equals(userId)) {
            throw ApiException.badRequest("Hold belongs to a different user");
        }

        // SELECT ... FOR UPDATE. Any other transaction asking for these
        // rows blocks here until this one commits or rolls back, so the
        // read below and the write further down cannot be interleaved.
        List<Seat> seats = seatRepository.findAllByIdForUpdate(hold.seatIds());

        if (seats.size() != hold.seatIds().size()) {
            throw ApiException.notFound("One or more held seats no longer exist");
        }
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

        try {
            for (Seat seat : seats) {
                seat.setStatus(SeatStatus.BOOKED);
                bookingSeatRepository.save(BookingSeat.builder()
                        .bookingId(booking.getId())
                        .seatId(seat.getId())
                        .active(true)
                        .build());
            }
            seatRepository.saveAll(seats);
            bookingSeatRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // The partial unique index rejected the insert, meaning this
            // seat is already in an active booking. Reaching here would
            // mean layers 1 and 2 both failed — it should be unreachable,
            // and is here so that if it ever happens the user sees a 409
            // rather than a 500.
            log.error("Partial unique index rejected booking for seats {} — "
                    + "the Redis claim or row lock did not hold", hold.seatIds(), e);
            throw ApiException.conflict("One or more seats were booked by someone else");
        }

        holdStore.release(hold.holdId());

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
        List<Long> seatIds = links.stream().map(BookingSeat::getSeatId).sorted().toList();

        // Lock the seats before releasing them, so a booking landing at
        // the same instant cannot read a stale status.
        List<Seat> seats = seatRepository.findAllByIdForUpdate(seatIds);

        // active = false rather than deleting: the partial unique index
        // frees the seat for a new booking while the history survives.
        for (BookingSeat link : links) {
            link.setActive(false);
        }
        bookingSeatRepository.saveAll(links);

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
    public BookingResponse getBooking(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> ApiException.notFound("No booking with id " + bookingId));
        // 404 rather than 403 for someone else's booking: a 403 would
        // confirm the id exists, which is itself information.
        if (!booking.getUserId().equals(userId)) {
            throw ApiException.notFound("No booking with id " + bookingId);
        }
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

    // ── Orphan sweeper ───────────────────────────────────────────

    /**
     * Returns seats stuck at HELD whose Redis claim has expired.
     *
     * Still needed after the Redis migration, and worth knowing why:
     * Redis expiring a key does not touch Postgres. The seat row stays
     * HELD forever unless something notices the claim is gone. So the
     * sweep is driven from the DB side — find HELD rows, ask Redis
     * whether each is still claimed, release the ones that are not.
     */
    @Transactional
    public int releaseOrphanedHolds() {
        List<Seat> held = seatRepository.findByStatus(SeatStatus.HELD);
        if (held.isEmpty()) {
            return 0;
        }

        List<Long> heldIds = held.stream().map(Seat::getId).toList();
        List<Long> stillClaimed = holdStore.claimedSeats(heldIds);

        int released = 0;
        for (Seat seat : held) {
            if (!stillClaimed.contains(seat.getId())) {
                seat.setStatus(SeatStatus.AVAILABLE);
                released++;
            }
        }
        if (released > 0) {
            seatRepository.saveAll(held);
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
