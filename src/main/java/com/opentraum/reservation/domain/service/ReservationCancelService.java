package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.client.EventServiceClient;
import com.opentraum.reservation.domain.entity.Reservation;
import com.opentraum.reservation.domain.entity.ReservationStatus;
import com.opentraum.reservation.domain.repository.ReservationRepository;
import com.opentraum.reservation.domain.repository.ReservationSeatRepository;
import com.opentraum.reservation.global.exception.BusinessException;
import com.opentraum.reservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationCancelService {

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final CancellationWindowService cancellationWindowService;
    private final SeatPoolService seatPoolService;
    private final EventServiceClient eventServiceClient;

    public Mono<Void> cancelReservation(Long reservationId, Long userId) {
        return reservationRepository.findById(reservationId)
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND)))
                .filter(r -> r.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.UNAUTHORIZED)))
                .flatMap(reservation -> cancellationWindowService.validateCancellationAllowed(reservation.getScheduleId())
                        .thenReturn(reservation))
                .flatMap(reservation -> {
                    String status = reservation.getStatus();
                    if (ReservationStatus.CANCELLED.name().equals(status) || ReservationStatus.REFUNDED.name().equals(status)) {
                        return Mono.error(new BusinessException(ErrorCode.RESERVATION_CANCELLED));
                    }
                    if (!ReservationStatus.ASSIGNED.name().equals(status) && !ReservationStatus.PAID_PENDING_SEAT.name().equals(status)) {
                        return Mono.error(new BusinessException(ErrorCode.INVALID_INPUT));
                    }
                    return Mono.just(reservation);
                })
                .flatMap(this::applyCancellation)
                .doOnSuccess(v -> log.info("예약 취소 완료: reservationId={}, userId={}", reservationId, userId))
                .then();
    }

    private Mono<Void> applyCancellation(Reservation reservation) {
        reservation.setStatus(ReservationStatus.REFUNDED.name());
        reservation.setUpdatedAt(LocalDateTime.now());
        Long reservationId = reservation.getId();
        Long scheduleId = reservation.getScheduleId();
        return reservationRepository.save(reservation)
                .then(reservationSeatRepository.findByReservationId(reservationId).collectList())
                .flatMap(seats -> reservationSeatRepository.updateStatusToCancelledByReservationId(reservationId)
                        .thenReturn(seats))
                .flatMap(seats -> Flux.fromIterable(seats)
                        .filter(rs -> rs.getZone() != null && rs.getSeatNumber() != null)
                        .flatMap(rs -> seatPoolService.returnSeat(scheduleId, rs.getZone(), rs.getSeatNumber())
                                .then(eventServiceClient.updateSeatStatus(scheduleId, rs.getZone(), rs.getSeatNumber(), "AVAILABLE")))
                        .then());
    }
}
