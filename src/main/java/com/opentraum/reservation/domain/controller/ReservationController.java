package com.opentraum.reservation.domain.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.opentraum.reservation.domain.dto.ReservationRequest;
import com.opentraum.reservation.domain.dto.ReservationResponse;
import com.opentraum.reservation.domain.service.ReservationService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ReservationResponse> createReservation(@RequestBody ReservationRequest request) {
        return reservationService.createReservation(request);
    }

    @GetMapping("/{id}")
    public Mono<ReservationResponse> getReservation(@PathVariable Long id) {
        return reservationService.getReservation(id);
    }

    @PutMapping("/{id}/confirm")
    public Mono<ReservationResponse> confirmReservation(@PathVariable Long id) {
        return reservationService.confirmReservation(id);
    }

    @PutMapping("/{id}/cancel")
    public Mono<ReservationResponse> cancelReservation(@PathVariable Long id) {
        return reservationService.cancelReservation(id);
    }

    @GetMapping
    public Flux<ReservationResponse> getReservationsByEvent(
            @RequestParam Long eventId,
            @RequestParam Long tenantId) {
        return reservationService.getReservationsByEvent(eventId, tenantId);
    }
}
