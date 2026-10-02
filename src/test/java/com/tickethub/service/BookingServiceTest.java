package com.tickethub.service;

import com.tickethub.domain.*;
import com.tickethub.repository.*;
import com.tickethub.service.hold.HoldStore;
import com.tickethub.service.hold.SeatHold;
import com.tickethub.web.dto.ConfirmRequest;
import com.tickethub.web.dto.HoldRequest;
import com.tickethub.web.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Fast unit tests for the branches that do not need a database:
 * validation, ownership, and what happens when the claim is refused.
 *
 * The concurrency guarantees are deliberately NOT tested here — mocks
 * cannot demonstrate a race. Those live in ConcurrentBookingTest against
 * real Postgres and Redis.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingServiceTest {

    @Mock SeatRepository seatRepository;
    @Mock BookingRepository bookingRepository;
    @Mock BookingSeatRepository bookingSeatRepository;
    @Mock UserRepository userRepository;
    @Mock HoldStore holdStore;

    @InjectMocks BookingService bookingService;

    private static final Long USER_ID = 1L;
    private static final Long EVENT_ID = 1L;

    @BeforeEach
    void setUp() {
        // @Value fields are populated by Spring at runtime; in a plain
        // unit test they have to be set directly.
        ReflectionTestUtils.setField(bookingService, "holdSeconds", 120L);
        ReflectionTestUtils.setField(bookingService, "maxSeatsPerBooking", 8);
        when(userRepository.existsById(USER_ID)).thenReturn(true);
    }

    private Seat seat(Long id, SeatStatus status) {
        return Seat.builder()
                .id(id).eventId(EVENT_ID).section("A").rowLabel("1")
                .seatNumber(id.intValue()).priceCents(1000L)
                .status(status).version(0L)
                .build();
    }

    @Test
    @DisplayName("rejects a booking larger than the per-booking cap")
    void rejectsTooManySeats() {
        HoldRequest request = new HoldRequest(EVENT_ID, List.of(1L,2L,3L,4L,5L,6L,7L,8L,9L));

        assertThatThrownBy(() -> bookingService.hold(request, USER_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("more than 8 seats");

        verify(holdStore, never()).tryClaim(any());
    }

    @Test
    @DisplayName("rejects an unknown user")
    void rejectsUnknownUser() {
        when(userRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() ->
                bookingService.hold(new HoldRequest(EVENT_ID, List.of(1L)), 99L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No user with id 99");
    }

    @Test
    @DisplayName("rejects a seat belonging to a different event")
    void rejectsSeatFromAnotherEvent() {
        Seat foreign = seat(1L, SeatStatus.AVAILABLE);
        foreign.setEventId(999L);
        when(seatRepository.findAllById(anyList())).thenReturn(List.of(foreign));

        assertThatThrownBy(() ->
                bookingService.hold(new HoldRequest(EVENT_ID, List.of(1L)), USER_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not belong to event");
    }

    @Test
    @DisplayName("rejects a seat that is already booked")
    void rejectsBookedSeat() {
        when(seatRepository.findAllById(anyList()))
                .thenReturn(List.of(seat(1L, SeatStatus.BOOKED)));

        assertThatThrownBy(() ->
                bookingService.hold(new HoldRequest(EVENT_ID, List.of(1L)), USER_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already booked");
    }

    @Test
    @DisplayName("a refused claim leaves the seat untouched in the database")
    void refusedClaimWritesNothing() {
        when(seatRepository.findAllById(anyList()))
                .thenReturn(List.of(seat(1L, SeatStatus.AVAILABLE)));
        when(holdStore.tryClaim(any())).thenReturn(false);
        when(holdStore.claimedSeats(anyList())).thenReturn(List.of(1L));

        assertThatThrownBy(() ->
                bookingService.hold(new HoldRequest(EVENT_ID, List.of(1L)), USER_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("being booked by someone else");

        // The important assertion: no partial write when the claim fails.
        verify(seatRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("a granted claim marks the seats HELD")
    void grantedClaimMarksSeatsHeld() {
        Seat free = seat(1L, SeatStatus.AVAILABLE);
        when(seatRepository.findAllById(anyList())).thenReturn(List.of(free));
        when(holdStore.tryClaim(any())).thenReturn(true);

        var response = bookingService.hold(new HoldRequest(EVENT_ID, List.of(1L)), USER_ID);

        assertThat(response.seatIds()).containsExactly(1L);
        assertThat(response.totalCents()).isEqualTo(1000L);
        assertThat(free.getStatus()).isEqualTo(SeatStatus.HELD);
        verify(seatRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("confirming an expired or unknown hold fails")
    void confirmRejectsMissingHold() {
        when(holdStore.get("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                bookingService.confirm(new ConfirmRequest("ghost"), USER_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("a user cannot confirm somebody else's hold")
    void confirmRejectsOtherUsersHold() {
        SeatHold hold = new SeatHold("h1", 42L, EVENT_ID, List.of(1L), 1000L,
                Instant.now().plusSeconds(60));
        when(holdStore.get("h1")).thenReturn(Optional.of(hold));

        assertThatThrownBy(() ->
                bookingService.confirm(new ConfirmRequest("h1"), USER_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("different user");
    }

    @Test
    @DisplayName("confirming a seat no longer HELD fails rather than overwriting")
    void confirmRejectsSeatNoLongerHeld() {
        SeatHold hold = new SeatHold("h1", USER_ID, EVENT_ID, List.of(1L), 1000L,
                Instant.now().plusSeconds(60));
        when(holdStore.get("h1")).thenReturn(Optional.of(hold));
        when(seatRepository.findAllByIdForUpdate(anyList()))
                .thenReturn(List.of(seat(1L, SeatStatus.AVAILABLE)));

        assertThatThrownBy(() ->
                bookingService.confirm(new ConfirmRequest("h1"), USER_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no longer held");

        verify(bookingRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelling someone else's booking is refused")
    void cancelRejectsOtherUsersBooking() {
        Booking booking = Booking.builder()
                .id(5L).userId(42L).eventId(EVENT_ID)
                .status(BookingStatus.CONFIRMED).totalCents(1000L)
                .build();
        when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancel(5L, USER_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("different user");
    }

    @Test
    @DisplayName("cancelling twice is refused")
    void cancelRejectsAlreadyCancelled() {
        Booking booking = Booking.builder()
                .id(5L).userId(USER_ID).eventId(EVENT_ID)
                .status(BookingStatus.CANCELLED).totalCents(1000L)
                .build();
        when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancel(5L, USER_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already cancelled");
    }
}
