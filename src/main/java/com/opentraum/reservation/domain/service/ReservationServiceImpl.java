package com.opentraum.reservation.domain.service;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opentraum.reservation.domain.dto.ReservationRequest;
import com.opentraum.reservation.domain.dto.ReservationResponse;
import com.opentraum.reservation.domain.entity.Reservation;
import com.opentraum.reservation.domain.entity.Reservation.ReservationStatus;
import com.opentraum.reservation.domain.repository.ReservationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationServiceImpl implements ReservationService {

    private final ReservationRepository reservationRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    @Transactional
    public Mono<ReservationResponse> createReservation(ReservationRequest request) {
        Reservation reservation = Reservation.builder()
                .userId(request.userId())
                .eventId(request.eventId())
                .seatId(request.seatId())
                .tenantId(request.tenantId())
                .status(ReservationStatus.PENDING)
                .build();

        return reservationRepository.save(reservation)
                .doOnSuccess(saved -> {
                    log.info("Reservation created: id={}, eventId={}, userId={}",
                            saved.getId(), saved.getEventId(), saved.getUserId());
                    kafkaTemplate.send("reservation-events",
                            String.valueOf(saved.getId()),
                            String.format("{\"type\":\"RESERVATION_CREATED\",\"reservationId\":%d,\"eventId\":%d,\"seatId\":%d,\"tenantId\":%d}",
                                    saved.getId(), saved.getEventId(), saved.getSeatId(), saved.getTenantId()));
                })
                .map(ReservationResponse::from);
    }

    @Override
    public Mono<ReservationResponse> getReservation(Long id) {
        return reservationRepository.findById(id)
                .map(ReservationResponse::from)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Reservation not found: " + id)));
    }

    @Override
    @Transactional
    public Mono<ReservationResponse> confirmReservation(Long id) {
        return reservationRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Reservation not found: " + id)))
                .flatMap(reservation -> {
                    reservation.setStatus(ReservationStatus.CONFIRMED);
                    return reservationRepository.save(reservation);
                })
                .doOnSuccess(confirmed -> {
                    log.info("Reservation confirmed: id={}", confirmed.getId());
                    kafkaTemplate.send("reservation-events",
                            String.valueOf(confirmed.getId()),
                            String.format("{\"type\":\"RESERVATION_CONFIRMED\",\"reservationId\":%d,\"eventId\":%d,\"tenantId\":%d}",
                                    confirmed.getId(), confirmed.getEventId(), confirmed.getTenantId()));
                })
                .map(ReservationResponse::from);
    }

    @Override
    @Transactional
    public Mono<ReservationResponse> cancelReservation(Long id) {
        return reservationRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Reservation not found: " + id)))
                .flatMap(reservation -> {
                    reservation.setStatus(ReservationStatus.CANCELLED);
                    return reservationRepository.save(reservation);
                })
                .doOnSuccess(cancelled -> {
                    log.info("Reservation cancelled: id={}", cancelled.getId());
                    kafkaTemplate.send("reservation-events",
                            String.valueOf(cancelled.getId()),
                            String.format("{\"type\":\"RESERVATION_CANCELLED\",\"reservationId\":%d,\"eventId\":%d,\"seatId\":%d,\"tenantId\":%d}",
                                    cancelled.getId(), cancelled.getEventId(), cancelled.getSeatId(), cancelled.getTenantId()));
                })
                .map(ReservationResponse::from);
    }

    @Override
    public Flux<ReservationResponse> getReservationsByEvent(Long eventId, Long tenantId) {
        return reservationRepository.findByEventIdAndTenantId(eventId, tenantId)
                .map(ReservationResponse::from);
    }
}
