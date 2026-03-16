package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.dto.ReservationRequest;
import com.opentraum.reservation.domain.dto.ReservationResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ReservationService {

    Mono<ReservationResponse> createReservation(ReservationRequest request);

    Mono<ReservationResponse> getReservation(Long id);

    Mono<ReservationResponse> confirmReservation(Long id);

    Mono<ReservationResponse> cancelReservation(Long id);

    Flux<ReservationResponse> getReservationsByEvent(Long eventId, Long tenantId);
}
