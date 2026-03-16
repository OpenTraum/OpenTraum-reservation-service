package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.dto.MyReservationResponse;
import com.opentraum.reservation.domain.dto.ReservationResponse;
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
public class ReservationQueryService {

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;

    // 결제 대기 중인(PENDING) 예약 조회
    public Mono<ReservationResponse> getPendingReservation(Long userId, Long scheduleId) {
        return reservationRepository.findFirstByUserIdAndScheduleIdAndStatusOrderByCreatedAtDesc(
                        userId, scheduleId, ReservationStatus.PENDING.name())
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND)))
                .map(this::toReservationResponse)
                .doOnSuccess(response -> log.info("결제 대기 중 예약 조회: userId={}, scheduleId={}, reservationId={}",
                        userId, scheduleId, response.getId()));
    }

    // 마이페이지: 내 전체 예매 내역
    public Flux<MyReservationResponse> getMyReservations(Long userId) {
        return reservationRepository.findByUserId(userId)
                .flatMap(reservation -> reservationSeatRepository.findByReservationId(reservation.getId())
                        .map(rs -> MyReservationResponse.SeatInfo.builder()
                                .zone(rs.getZone())
                                .seatNumber(rs.getSeatNumber())
                                .status(rs.getStatus())
                                .build())
                        .collectList()
                        .map(seats -> MyReservationResponse.builder()
                                .id(reservation.getId())
                                .scheduleId(reservation.getScheduleId())
                                .grade(reservation.getGrade())
                                .quantity(reservation.getQuantity())
                                .trackType(reservation.getTrackType())
                                .status(reservation.getStatus())
                                .seats(seats)
                                .createdAt(reservation.getCreatedAt())
                                .build()));
    }

    private ReservationResponse toReservationResponse(Reservation reservation) {
        LocalDateTime paymentDeadline = reservation.getCreatedAt() != null ?
                reservation.getCreatedAt().plusMinutes(5) : null;
        return ReservationResponse.builder()
                .id(reservation.getId())
                .scheduleId(reservation.getScheduleId())
                .grade(reservation.getGrade())
                .quantity(reservation.getQuantity())
                .trackType(reservation.getTrackType())
                .status(reservation.getStatus())
                .paymentDeadline(paymentDeadline)
                .build();
    }
}
