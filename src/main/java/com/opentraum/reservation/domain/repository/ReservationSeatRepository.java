package com.opentraum.reservation.domain.repository;

import com.opentraum.reservation.domain.entity.ReservationSeat;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface ReservationSeatRepository extends ReactiveCrudRepository<ReservationSeat, Long> {

    Flux<ReservationSeat> findByReservationId(Long reservationId);

    Flux<ReservationSeat> findByStatusAndCreatedAtBefore(String status, LocalDateTime before);

    @Modifying
    @Query("UPDATE reservation_seats SET status = 'CANCELLED' WHERE reservation_id = :reservationId")
    Mono<Integer> updateStatusToCancelledByReservationId(Long reservationId);
}
