package com.tickethub.web.dto;

import com.tickethub.domain.SeatStatus;

public record SeatResponse(
        Long id,
        String section,
        String rowLabel,
        Integer seatNumber,
        Long priceCents,
        SeatStatus status
) {}
