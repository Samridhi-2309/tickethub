package com.tickethub.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "seats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Kept as a plain FK column rather than a @ManyToOne association:
    // the seat map query never needs the Event object, and this avoids
    // an N+1 fetch when loading 200 seats.
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(nullable = false)
    private String section;

    @Column(name = "row_label", nullable = false)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @Column(name = "price_cents", nullable = false)
    private Long priceCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SeatStatus status;

    // JPA optimistic locking. Hibernate appends "WHERE version = ?" to
    // updates and throws OptimisticLockException if no row matches.
    @Version
    @Column(nullable = false)
    private Long version;
}
