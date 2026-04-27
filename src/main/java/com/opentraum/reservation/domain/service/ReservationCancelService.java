package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.entity.Reservation;
import com.opentraum.reservation.domain.entity.ReservationStatus;
import com.opentraum.reservation.domain.outbox.service.OutboxService;
import com.opentraum.reservation.domain.repository.ReservationRepository;
import com.opentraum.reservation.domain.repository.ReservationSeatRepository;
import com.opentraum.reservation.global.exception.BusinessException;
import com.opentraum.reservation.global.exception.ErrorCode;
import com.opentraum.reservation.global.util.SagaIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationCancelService {

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final CancellationWindowService cancellationWindowService;
    private final OutboxService outboxService;
    private final TransactionalOperator txOperator;

    /**
     * 시스템 내부 취소(홀드 만료/미당첨 등)에서 사용하는 진입점.
     * 사용자 취소 API는 {@link ReservationSagaService#userCancel}을 사용한다.
     */
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
                .flatMap(r -> applyCancellation(r, "USER_CANCELLED"))
                .doOnSuccess(v -> log.info("예약 취소 완료: reservationId={}, userId={}", reservationId, userId))
                .then();
    }

    /**
     * 예약을 REFUNDED로 전이하고 {@code ReservationCancelled} Outbox 이벤트를 발행한다.
     *
     * <p>좌석 풀 반환과 event-service DB 좌석 상태(AVAILABLE) 전이는 이 이벤트를 수신한
     * event-service가 배치로 처리한다. 기존 N*2 REST 호출은 모두 제거되었다.
     */
    public Mono<Void> applyCancellation(Reservation reservation, String reason) {
        if (reservation.getSagaId() == null) {
            reservation.setSagaId(SagaIdGenerator.newSagaId());
        }
        reservation.setStatus(ReservationStatus.REFUNDED.name());
        reservation.setUpdatedAt(LocalDateTime.now());
        Long reservationId = reservation.getId();

        Mono<Void> work = reservationRepository.save(reservation)
                .then(reservationSeatRepository.updateStatusToCancelledByReservationId(reservationId))
                .then(publishReservationCancelled(reservation, reason));

        return txOperator.transactional(work);
    }

    private Mono<Void> publishReservationCancelled(Reservation reservation, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reason", reason);
        return outboxService.publish(
                        reservation.getId(), "reservation", "ReservationCancelled",
                        reservation.getSagaId(), payload)
                .then();
    }
}
