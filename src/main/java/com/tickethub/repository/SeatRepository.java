package com.tickethub.repository;

import com.tickethub.domain.Seat;
import com.tickethub.domain.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEventIdOrderByRowLabelAscSeatNumberAsc(Long eventId);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    /**
     * Day 3 uses this. PESSIMISTIC_WRITE makes Hibernate emit
     * SELECT ... FOR UPDATE, so concurrent transactions asking for the
     * same seat rows block until this transaction commits or rolls back.
     *
     * ORDER BY s.id is not cosmetic: locking multi-seat bookings in a
     * consistent order prevents two transactions deadlocking by grabbing
     * the same two seats in opposite orders.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id IN :ids ORDER BY s.id")
    List<Seat> findAllByIdForUpdate(@Param("ids") List<Long> ids);
}
