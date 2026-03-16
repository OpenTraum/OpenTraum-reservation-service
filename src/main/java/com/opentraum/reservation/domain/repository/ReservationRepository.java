package com.opentraum.reservation.domain.repository;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

import com.opentraum.reservation.domain.entity.Reservation;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ReservationRepository extends R2dbcRepository<Reservation, Long> {

    Flux<Reservation> findByUserIdAndEventId(Long userId, Long eventId);

    Flux<Reservation> findByEventIdAndTenantId(Long eventId, Long tenantId);

    Mono<Reservation> findByUserIdAndEventIdAndSeatId(Long userId, Long eventId, Long seatId);

    Flux<Reservation> findByTenantId(Long tenantId);
}
