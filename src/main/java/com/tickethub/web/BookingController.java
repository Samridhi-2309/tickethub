package com.tickethub.web;

import com.tickethub.security.AppUserDetails;
import com.tickethub.service.BookingService;
import com.tickethub.service.IdempotencyService;
import com.tickethub.web.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Every method takes the user from the authenticated principal, never
 * from the request body. Before Day 4 the client sent its own userId,
 * which meant anyone could book as anyone.
 */
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final IdempotencyService idempotencyService;

    /** Step 1: claim seats for a short window while the user checks out. */
    @PostMapping("/hold")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse hold(@Valid @RequestBody HoldRequest request,
                             @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.hold(request, principal.getUserId());
    }

    /**
     * Step 2: turn a live hold into a confirmed booking.
     *
     * Send an Idempotency-Key header and the request becomes safe to
     * retry: a repeat with the same key replays the first response
     * instead of creating a second booking.
     */
    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse confirm(
            @Valid @RequestBody ConfirmRequest request,
            @AuthenticationPrincipal AppUserDetails principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = principal.getUserId();
        return idempotencyService.execute(
                idempotencyKey,
                userId,
                request,
                BookingResponse.class,
                () -> bookingService.confirm(request, userId));
    }

    @PostMapping("/{bookingId}/cancel")
    public BookingResponse cancel(@PathVariable Long bookingId,
                                  @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.cancel(bookingId, principal.getUserId());
    }

    @GetMapping("/{bookingId}")
    public BookingResponse get(@PathVariable Long bookingId,
                               @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.getBooking(bookingId, principal.getUserId());
    }

    @GetMapping
    public List<BookingResponse> list(@AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.listBookings(principal.getUserId());
    }
}
