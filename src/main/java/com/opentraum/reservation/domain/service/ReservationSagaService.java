package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.entity.Reservation;
import com.opentraum.reservation.domain.entity.ReservationStatus;
import com.opentraum.reservation.domain.entity.TrackType;
import com.opentraum.reservation.domain.outbox.service.OutboxService;
import com.opentraum.reservation.domain.repository.ReservationRepository;
import com.opentraum.reservation.domain.saga.SeatRef;
import com.opentraum.reservation.global.exception.BusinessException;
import com.opentraum.reservation.global.exception.ErrorCode;
import com.opentraum.reservation.global.util.RedisKeyGenerator;
import com.opentraum.reservation.global.util.SagaIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SAGA Orchestrator. 예약 엔티티 상태 변경과 Outbox 이벤트 기록을
 * 단일 DB 트랜잭션으로 원자화한다.
 *
 * <p>이벤트 payload는 {@code /tmp/outbox-opentraum/EVENT-SCHEMA.md} 합의문에 따른다.
 * 공통 필드(saga_id, reservation_id, occurred_at)는 {@link OutboxService}가 자동 주입한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationSagaService {

    private static final String AGGREGATE_TYPE = "reservation";

    private final ReservationRepository reservationRepository;
    private final OutboxService outboxService;
    private final TransactionalOperator txOperator;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final CancellationWindowService cancellationWindowService;
    private final LiveTrackService liveTrackService;

    /**
     * Live 예약 생성 + ReservationCreated 발행.
     *
     * <p>호출 측은 이미 좌석 풀/홀드 선점을 끝낸 상태여야 한다. 이 메서드는
     * {@code reservations} INSERT/UPDATE와 Outbox INSERT를 동일 트랜잭션으로 묶는다.
     */
    public Mono<Reservation> createLiveReservation(Reservation reservation, List<SeatRef> seats) {
        if (reservation.getSagaId() == null) {
            reservation.setSagaId(SagaIdGenerator.newSagaId());
        }
        reservation.setTrackType(TrackType.LIVE.name());
        if (reservation.getStatus() == null) {
            reservation.setStatus(ReservationStatus.PENDING.name());
        }

        return reservationRepository.save(reservation)
                .flatMap(saved -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("user_id", saved.getUserId());
                    payload.put("schedule_id", saved.getScheduleId());
                    payload.put("track_type", TrackType.LIVE.name());
                    payload.put("grade", saved.getGrade());
                    payload.put("seats", seats);
                    return outboxService.publish(
                                    saved.getId(), AGGREGATE_TYPE, "ReservationCreated",
                                    saved.getSagaId(), payload)
                            .thenReturn(saved);
                })
                .as(txOperator::transactional);
    }

    /**
     * Lottery 예약 생성 + LotteryReservationCreated 발행.
     */
    public Mono<Reservation> createLotteryReservation(Reservation reservation) {
        if (reservation.getSagaId() == null) {
            reservation.setSagaId(SagaIdGenerator.newSagaId());
        }
        reservation.setTrackType(TrackType.LOTTERY.name());
        if (reservation.getStatus() == null) {
            reservation.setStatus(ReservationStatus.PENDING.name());
        }

        return reservationRepository.save(reservation)
                .flatMap(saved -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("user_id", saved.getUserId());
                    payload.put("schedule_id", saved.getScheduleId());
                    payload.put("track_type", TrackType.LOTTERY.name());
                    payload.put("grade", saved.getGrade());
                    payload.put("quantity", saved.getQuantity());
                    return outboxService.publish(
                                    saved.getId(), AGGREGATE_TYPE, "LotteryReservationCreated",
                                    saved.getSagaId(), payload)
                            .thenReturn(saved);
                })
                .as(txOperator::transactional);
    }

    /**
     * Live 결제 완료 확정: reservation PENDING → PAID, reservation_seats PENDING → ASSIGNED.
     */
    public Mono<Void> confirmLive(String sagaId, Long reservationId, Long paymentId) {
        return reservationRepository.findById(reservationId)
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND)))
                .flatMap(reservation -> {
                    if (ReservationStatus.PAID.name().equals(reservation.getStatus())) {
                        return Mono.empty();
                    }
                    reservation.setStatus(ReservationStatus.PAID.name());
                    reservation.setUpdatedAt(LocalDateTime.now());
                    return reservationRepository.save(reservation)
                            .flatMap(saved -> liveTrackService.onPaymentCompleted(saved.getId())
                                    .then(publishConfirmed(saved, paymentId)));
                })
                .as(txOperator::transactional)
                .then();
    }

    /**
     * Lottery 결제 완료 확정: PENDING → PAID_PENDING_SEAT.
     *
     * <p>트랜잭션 종료 후 {@code lottery-paid:{scheduleId}} Redis set에 userId를 추가해
     * 양 트랙 중복 참여 차단({@code canParticipate})이 유지되도록 한다.
     */
    public Mono<Void> confirmLottery(String sagaId, Long reservationId, Long paymentId) {
        return reservationRepository.findById(reservationId)
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND)))
                .flatMap(reservation -> {
                    String status = reservation.getStatus();
                    if (ReservationStatus.PAID_PENDING_SEAT.name().equals(status)
                            || ReservationStatus.ASSIGNED.name().equals(status)) {
                        return Mono.just(reservation);
                    }
                    reservation.setStatus(ReservationStatus.PAID_PENDING_SEAT.name());
                    reservation.setUpdatedAt(LocalDateTime.now());
                    return reservationRepository.save(reservation)
                            .flatMap(saved -> publishConfirmed(saved, paymentId));
                })
                .as(txOperator::transactional)
                .flatMap(reservation -> redisTemplate.opsForSet()
                        .add(RedisKeyGenerator.lotteryPaidKey(reservation.getScheduleId()),
                                reservation.getUserId().toString())
                        .then())
                .then();
    }

    /**
     * 결제 실패 보상: 예약을 CANCELLED로 전이하고 ReservationCancelled 발행.
     * event-service가 이 이벤트로 좌석 SeatReleased 배치를 처리한다.
     */
    public Mono<Void> cancelBySagaFailure(String sagaId, Long reservationId, String reason) {
        return reservationRepository.findById(reservationId)
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND)))
                .flatMap(reservation -> {
                    if (ReservationStatus.CANCELLED.name().equals(reservation.getStatus())
                            || ReservationStatus.REFUNDED.name().equals(reservation.getStatus())) {
                        return Mono.empty();
                    }
                    reservation.setStatus(ReservationStatus.CANCELLED.name());
                    reservation.setUpdatedAt(LocalDateTime.now());
                    return reservationRepository.save(reservation)
                            .flatMap(saved -> publishCancelled(saved, reason));
                })
                .as(txOperator::transactional)
                .then();
    }

    /**
     * 사용자 취소 SAGA 진입점.
     * <ul>
     *   <li>미결제(PENDING): 즉시 ReservationCancelled 발행 (좌석만 회수)</li>
     *   <li>결제 완료(PAID / PAID_PENDING_SEAT / ASSIGNED): ReservationRefundRequested 발행
     *       → payment가 RefundCompleted 응답 시 {@link #confirmRefund}로 REFUNDED 확정</li>
     * </ul>
     */
    public Mono<Void> userCancel(Long reservationId, Long userId) {
        return reservationRepository.findById(reservationId)
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND)))
                .filter(r -> r.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.UNAUTHORIZED)))
                .flatMap(reservation -> cancellationWindowService.validateCancellationAllowed(reservation.getScheduleId())
                        .thenReturn(reservation))
                .flatMap(reservation -> {
                    String status = reservation.getStatus();
                    if (ReservationStatus.CANCELLED.name().equals(status)
                            || ReservationStatus.REFUNDED.name().equals(status)) {
                        return Mono.<Void>error(new BusinessException(ErrorCode.RESERVATION_CANCELLED));
                    }
                    if (reservation.getSagaId() == null) {
                        reservation.setSagaId(SagaIdGenerator.newSagaId());
                    }

                    // 미결제 예약은 즉시 취소 이벤트 발행, 결제 예약은 환불 SAGA 진입.
                    if (ReservationStatus.PENDING.name().equals(status)) {
                        reservation.setStatus(ReservationStatus.CANCELLED.name());
                        reservation.setUpdatedAt(LocalDateTime.now());
                        Mono<Void> work = reservationRepository.save(reservation)
                                .flatMap(saved -> publishCancelled(saved, "USER_CANCELLED"))
                                .then();
                        return txOperator.transactional(work);
                    }
                    Mono<Void> work = reservationRepository.save(reservation)
                            .flatMap(this::publishRefundRequested)
                            .then();
                    return txOperator.transactional(work);
                })
                .then();
    }

    /**
     * RefundCompleted 수신: REFUNDED로 확정.
     */
    public Mono<Void> confirmRefund(String sagaId, Long reservationId) {
        return reservationRepository.findById(reservationId)
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND)))
                .flatMap(reservation -> {
                    if (ReservationStatus.REFUNDED.name().equals(reservation.getStatus())) {
                        return Mono.empty();
                    }
                    reservation.setStatus(ReservationStatus.REFUNDED.name());
                    reservation.setUpdatedAt(LocalDateTime.now());
                    return reservationRepository.save(reservation).then();
                })
                .as(txOperator::transactional)
                .then();
    }

    // ───── internal helpers ─────

    private Mono<Reservation> publishConfirmed(Reservation reservation, Long paymentId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("payment_id", paymentId);
        return outboxService.publish(
                        reservation.getId(), AGGREGATE_TYPE, "ReservationConfirmed",
                        reservation.getSagaId(), payload)
                .thenReturn(reservation);
    }

    private Mono<Reservation> publishCancelled(Reservation reservation, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reason", reason);
        return outboxService.publish(
                        reservation.getId(), AGGREGATE_TYPE, "ReservationCancelled",
                        reservation.getSagaId(), payload)
                .thenReturn(reservation);
    }

    private Mono<Reservation> publishRefundRequested(Reservation reservation) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reason", "USER_CANCELLED");
        return outboxService.publish(
                        reservation.getId(), AGGREGATE_TYPE, "ReservationRefundRequested",
                        reservation.getSagaId(), payload)
                .thenReturn(reservation);
    }
}
