package com.tickethub.web;

import com.tickethub.service.BookingService;
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

    /** Step 1: claim seats for a short window while the user checks out. */
    @PostMapping("/hold")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse hold(@Valid @RequestBody HoldRequest request) {
        return bookingService.hold(request);
    }

    /** Step 2: turn a live hold into a confirmed booking. */
    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse confirm(@Valid @RequestBody ConfirmRequest request) {
        return bookingService.confirm(request);
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
