package com.tickethub.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Join row between a booking and a seat.
 *
 * A partial unique index in V1__init.sql enforces that a seat appears in
 * at most one row where active = true. That index is the last line of
 * defence against double booking: even if the service layer had a race,
 * the second INSERT would fail at the database.
 */
@Entity
@Table(name = "booking_seats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(nullable = false)
    private Boolean active;
}
