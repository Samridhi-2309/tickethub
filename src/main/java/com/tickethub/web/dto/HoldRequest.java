package com.tickethub.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Day 4 removed userId from this body. The user is taken from the
 * authenticated principal instead — a client that can name its own user
 * id can book seats as anyone else.
 */
public record HoldRequest(
        @NotNull Long eventId,
        @NotEmpty List<Long> seatIds
) {}
