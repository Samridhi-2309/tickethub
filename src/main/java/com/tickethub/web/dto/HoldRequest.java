package com.tickethub.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * userId is passed in the body only until Day 4, when JWT auth replaces it
 * with the authenticated principal. Do not ship this shape.
 */
public record HoldRequest(
        @NotNull Long userId,
        @NotNull Long eventId,
        @NotEmpty List<Long> seatIds
) {}
