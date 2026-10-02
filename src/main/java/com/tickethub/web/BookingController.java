package com.tickethub.web;

import com.tickethub.service.BookingService;
import com.tickethub.service.IdempotencyService;
import com.tickethub.web.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final IdempotencyService idempotencyService;

    /** Step 1: claim seats for a short window while the user checks out. */
    @PostMapping("/hold")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse hold(@Valid @RequestBody HoldRequest request) {
        return bookingService.hold(request);
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
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        return idempotencyService.execute(
                idempotencyKey,
                request.userId(),
                request,
                BookingResponse.class,
                () -> bookingService.confirm(request));
    }

    @PostMapping("/{bookingId}/cancel")
    public BookingResponse cancel(@PathVariable Long bookingId,
                                  @RequestParam Long userId) {
        return bookingService.cancel(bookingId, userId);
    }

    @GetMapping("/{bookingId}")
    public BookingResponse get(@PathVariable Long bookingId) {
        return bookingService.getBooking(bookingId);
    }

    @GetMapping
    public List<BookingResponse> list(@RequestParam Long userId) {
        return bookingService.listBookings(userId);
    }
}
