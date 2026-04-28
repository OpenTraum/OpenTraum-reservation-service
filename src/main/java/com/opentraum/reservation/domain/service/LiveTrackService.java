package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.client.EventServiceClient;
import com.opentraum.reservation.domain.client.dto.SeatHoldRequest;
import com.opentraum.reservation.domain.constants.ReservationConstants;
import com.opentraum.reservation.domain.dto.SeatSelectionRequest;
import com.opentraum.reservation.domain.dto.SeatSelectionResponse;
import com.opentraum.reservation.domain.entity.Reservation;
import com.opentraum.reservation.domain.entity.ReservationSeat;
import com.opentraum.reservation.domain.entity.ReservationSeatStatus;
import com.opentraum.reservation.domain.entity.ReservationStatus;
import com.opentraum.reservation.domain.entity.TrackType;
import com.opentraum.reservation.domain.outbox.service.OutboxService;
import com.opentraum.reservation.domain.repository.ReservationRepository;
import com.opentraum.reservation.domain.repository.ReservationSeatRepository;
import com.opentraum.reservation.domain.saga.SeatRef;
import com.opentraum.reservation.global.exception.BusinessException;
import com.opentraum.reservation.global.exception.ErrorCode;
import com.opentraum.reservation.global.util.RedisKeyGenerator;
import com.opentraum.reservation.global.util.SagaIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 라이브 트랙 좌석 선택 흐름.
 *
 * <p>Wave 3.1: Critical path 인 좌석 확보는 reservation-service 가 event-service
 * {@code POST /api/v1/internal/seats/hold} 를 <b>동기 REST</b> 로 호출해 받는다.
 * event-service 는 Redis SETNX + DB 원자 UPDATE + SeatHeld outbox 발행을 한 번에 수행하므로
 * 동일 좌석에 N 개 동시 요청이 들어와도 정확히 1 건만 200 OK 를 받고 나머지는 409 Conflict.
 *
 * <p>race condition 배경: 이전 Wave 3 에서는 reservation-service 가 event 조회 후 AVAILABLE
 * 여부만 확인하고 {@code ReservationCreated} outbox 만 발행해 HOLD 는 event-service
 * listener 에서 비동기로 처리했다. "read-then-write" 구조 때문에 20 병렬 테스트에서 4/20
 * 이 중복 성공했고, "200 OK 를 받았지만 실제 좌석은 다른 사람에게 간" 좀비 reservation
 * 이 발생했다 (2026-04-22 동시성 검증으로 확인).
 *
 * <p>Wave 3.1 에서도 reservation 로컬 (reservations, reservation_seats) 저장은 유지되고
 * 후속 SAGA 전파(SeatHeld → payment 요청 → PaymentCompleted → SeatConfirmed) 는 기존 그대로
 * Kafka outbox 경로를 따른다. 변경은 "좌석 확보 단 한 구간" 만 동기화한 것.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTrackService {

    private final LotteryTrackService lotteryTrackService;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final EventServiceClient eventServiceClient;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final OutboxService outboxService;
    private final SeatPoolService seatPoolService;

    // 좌석 선택 (라이브 트랙). 플로우: 등급 선택 → 구역 선택 → 좌석 선택
    public Mono<SeatSelectionResponse> selectSeat(Long scheduleId, SeatSelectionRequest request, Long userId) {
        if (request.getGrade() == null || request.getGrade().isBlank()) {
            return Mono.error(new BusinessException(ErrorCode.INVALID_INPUT));
        }
        // 1. 티켓 오픈 시간 체크
        return eventServiceClient.findScheduleOrThrow(scheduleId)
                .flatMap(schedule -> {
                    if ("LOTTERY_ONLY".equals(schedule.getTrackPolicy())) {
                        return Mono.error(new BusinessException(ErrorCode.TRACK_NOT_ALLOWED));
                    }
                    LocalDateTime now = LocalDateTime.now();
                    if (now.isBefore(schedule.getTicketOpenAt())) {
                        return Mono.error(new BusinessException(ErrorCode.TICKET_NOT_OPENED));
                    }
                    return Mono.just(schedule);
                })
                // 2. 라이브 트랙 큐 마감 여부만 체크
                .then(requireLiveTrackOpen(isLiveTrackOpen(scheduleId)))
                // 3. 추첨 결제 완료자 라이브 참여 불가
                .then(requireTrue(
                        lotteryTrackService.canParticipate(scheduleId, userId),
                        ErrorCode.ALREADY_PARTICIPATED))
                // 4. 라이브 1인당 최대 4장 제한
                .then(requireTrue(
                        reservationRepository.sumLiveQuantityByUserAndSchedule(scheduleId, userId)
                                .map(qty -> (qty != null ? qty : 0L) < ReservationConstants.LIVE_MAX_QUANTITY_PER_USER),
                        ErrorCode.LIVE_MAX_QUANTITY_EXCEEDED))
                // 5. 등급·구역 검증
                .then(eventServiceClient.validateGradeAndZone(scheduleId, request.getGrade(), request.getZone()))
                // 6. reservation 확보 (기존 있으면 재사용, 없으면 PENDING 생성 — 아직 좌석 붙이지 않음)
                .then(reservationRepository.findFirstByUserIdAndScheduleIdAndTrackType(
                                userId, scheduleId, TrackType.LIVE.name())
                        .switchIfEmpty(Mono.defer(() ->
                                reservationRepository.existsByUserIdAndScheduleIdAndStatusIn(
                                                userId, scheduleId, "PENDING", "PAID_PENDING_SEAT", "ASSIGNED", "PAID")
                                        .flatMap(exists -> exists
                                                ? Mono.<Reservation>error(new BusinessException(ErrorCode.ALREADY_PARTICIPATED))
                                                : reservationRepository.save(Reservation.builder()
                                                        .userId(userId)
                                                        .scheduleId(scheduleId)
                                                        .grade(request.getGrade())
                                                        .quantity(0)
                                                        .trackType(TrackType.LIVE.name())
                                                        .status(ReservationStatus.PENDING.name())
                                                        .sagaId(SagaIdGenerator.newSagaId())
                                                        .createdAt(LocalDateTime.now())
                                                        .updatedAt(LocalDateTime.now())
                                                        .build())))))
                // 7. event-service 동기 REST hold — race 방어의 핵심. 실패 시 생성된 reservation 도 롤백.
                .flatMap(reservation -> acquireHoldFromEventService(reservation, request)
                        .flatMap(seatInfoFromHold -> persistSeatAndBumpQuantity(
                                reservation, request.getZone(), request.getSeatNumber()))
                        // 좌석 hold 실패 시: 방금 save 한 reservation 이 좌석 없이 PENDING 으로 남는다.
                        // quantity=0 인 reservation 은 다음 요청에서 재사용되거나 만료 스케줄러가 회수하지만,
                        // 즉시 CANCELLED 로 전이해 "고아 PENDING" 을 남기지 않는 게 깔끔하다.
                        // 단, reservation 에 이미 다른 좌석이 붙어 있으면 (quantity > 0) 기존 좌석 보호를 위해
                        // 건드리지 않는다.
                        .onErrorResume(BusinessException.class, err -> {
                            if (reservation.getQuantity() > 0) {
                                return Mono.error(err);
                            }
                            reservation.setStatus(ReservationStatus.CANCELLED.name());
                            reservation.setUpdatedAt(LocalDateTime.now());
                            return reservationRepository.save(reservation)
                                    .then(Mono.<Reservation>error(err));
                        }))
                .map(reservation -> {
                    log.info("라이브 트랙 좌석 선택: reservationId={}, userId={}, grade={}, zone={}, seat={}",
                            reservation.getId(), userId, request.getGrade(), request.getZone(), request.getSeatNumber());
                    LocalDateTime now = LocalDateTime.now();
                    return SeatSelectionResponse.builder()
                            .scheduleId(scheduleId)
                            .grade(request.getGrade())
                            .zone(request.getZone())
                            .seatNumber(request.getSeatNumber())
                            .holdExpiresAt(now.plusMinutes(ReservationConstants.HOLD_MINUTES))
                            .paymentDeadline(now.plusMinutes(ReservationConstants.PAYMENT_DEADLINE_MINUTES))
                            .message(ReservationConstants.MESSAGE_PAYMENT_DEADLINE_LIVE)
                            .build();
                });
    }

    /**
     * event-service 동기 REST HOLD. race 방어.
     *
     * <p>event-service 쪽에서 Redis SETNX + DB 원자 UPDATE + SeatHeld outbox 발행까지 완료된다.
     * 동일 좌석에 N 개 동시 요청이 들어와도 정확히 1 건만 200 OK 를 받고 나머지는 409 →
     * {@link ErrorCode#SEAT_ALREADY_TAKEN} 로 변환되어 사용자에게 즉시 실패 응답.
     */
    private Mono<com.opentraum.reservation.domain.client.dto.SeatHoldResponse> acquireHoldFromEventService(
            Reservation reservation, SeatSelectionRequest request) {
        return eventServiceClient.findSeatByScheduleAndZoneAndNumber(
                        reservation.getScheduleId(), request.getZone(), request.getSeatNumber())
                .flatMap(seatInfo -> {
                    Long amount = seatInfo.getPrice() != null ? seatInfo.getPrice().longValue() : null;
                    SeatHoldRequest holdReq = SeatHoldRequest.builder()
                            .scheduleId(reservation.getScheduleId())
                            .zone(request.getZone())
                            .seatNumber(request.getSeatNumber())
                            .reservationId(reservation.getId())
                            .sagaId(reservation.getSagaId())
                            .userId(reservation.getUserId())
                            .trackType(TrackType.LIVE.name())
                            .amount(amount)
                            .build();
                    return eventServiceClient.tryHold(holdReq);
                });
    }

    /**
     * HOLD 성공 후 reservation 로컬 상태 갱신.
     *
     * <p>event-service 쪽 좌석 HOLD 는 이미 확정된 상태. 여기서는 reservation_seats 에
     * PENDING row 를 추가하고 reservation.quantity 를 증가시킨다.
     * Outbox 발행은 하지 않는다 (좌석 확보 이벤트는 event-service 가 SeatHeld 로 이미 발행했음).
     */
    private Mono<Reservation> persistSeatAndBumpQuantity(Reservation reservation, String zone, String seatNumber) {
        return buildAndSaveReservationSeat(
                        reservation.getId(), reservation.getScheduleId(), zone, seatNumber,
                        ReservationSeatStatus.PENDING.name())
                .then(Mono.defer(() -> {
                    reservation.setQuantity(reservation.getQuantity() + 1);
                    reservation.setUpdatedAt(LocalDateTime.now());
                    return reservationRepository.save(reservation);
                }));
    }

    /**
     * 사용자 수동 좌석 해제.
     *
     * <p>Wave 3부터 Redis 직접 조작을 제거하고 Outbox {@code ReservationCancelled}를 발행한다.
     * event-service가 이 이벤트를 수신해 좌석 HOLD/풀을 release하고, reservation 로컬에서는
     * 해당 reservation_seat를 CANCELLED로 전이한다. 응답은 "요청 접수됨"으로 간주한다.
     */
    public Mono<Void> releaseSeat(Long scheduleId, String zone, String seatNumber, Long userId) {
        return reservationRepository.findFirstByUserIdAndScheduleIdAndTrackType(userId, scheduleId, TrackType.LIVE.name())
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND)))
                .flatMap(reservation -> reservationSeatRepository.findByReservationId(reservation.getId())
                        .filter(rs -> zone.equals(rs.getZone()) && seatNumber.equals(rs.getSeatNumber())
                                && ReservationSeatStatus.PENDING.name().equals(rs.getStatus()))
                        .next()
                        .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.SEAT_HOLD_NOT_OWNED)))
                        // 원자 UPDATE: PENDING → CANCELLED (WHERE status='PENDING' 조건으로 race 방어).
                        // 동시 DELETE 2회 시 첫 요청만 rows=1, 두 번째는 rows=0 → quantity 감소 + outbox 발행 skip.
                        .flatMap(seat -> reservationSeatRepository.tryCancelPending(seat.getId())
                                .flatMap(rows -> {
                                    if (rows == 0) {
                                        // race 패자: 다른 요청이 이미 처리했음. 같은 최종 상태가 보장되므로 조용히 성공.
                                        return Mono.empty();
                                    }
                                    return Mono.defer(() -> {
                                        int newQty = Math.max(0, reservation.getQuantity() - 1);
                                        reservation.setQuantity(newQty);
                                        reservation.setUpdatedAt(LocalDateTime.now());
                                        if (reservation.getSagaId() == null) {
                                            reservation.setSagaId(SagaIdGenerator.newSagaId());
                                        }
                                        if (newQty == 0) {
                                            reservation.setStatus(ReservationStatus.CANCELLED.name());
                                            Map<String, Object> payload = new HashMap<>();
                                            payload.put("reason", "USER_RELEASED");
                                            payload.put("seats", List.of(SeatRef.builder()
                                                    .zone(zone).seatNumber(seatNumber).build()));
                                            return reservationRepository.save(reservation)
                                                    .then(outboxService.publish(
                                                            reservation.getId(), "reservation", "ReservationCancelled",
                                                            reservation.getSagaId(), payload))
                                                    .then();
                                        }
                                        // 부분 해제: 좌석 단위 릴리즈 이벤트
                                        Map<String, Object> payload = new HashMap<>();
                                        payload.put("reason", "USER_RELEASED");
                                        payload.put("seats", List.of(SeatRef.builder()
                                                .zone(zone).seatNumber(seatNumber).build()));
                                        return reservationRepository.save(reservation)
                                                .then(outboxService.publish(
                                                        reservation.getId(), "reservation", "ReservationSeatReleased",
                                                        reservation.getSagaId(), payload))
                                                .then();
                                    });
                                })))
                .then();
    }

    // 라이브 결제 완료 시 로컬 후속 처리: reservation_seats ASSIGNED 전이.
    // 예약 상태(PAID) 전이는 SAGA({@code ReservationSagaService#confirmLive})가 담당하고,
    // event DB 좌석 상태(SOLD)와 Redis HOLD 해제는 event-service가 SeatConfirmed 이벤트로 반영한다.
    public Mono<Void> onPaymentCompleted(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .filter(r -> TrackType.LIVE.name().equals(r.getTrackType()))
                .flatMap(reservation -> reservationSeatRepository.findByReservationId(reservationId)
                        .filter(rs -> ReservationSeatStatus.PENDING.name().equals(rs.getStatus()))
                        .flatMap(seat -> {
                            seat.setStatus(ReservationSeatStatus.ASSIGNED.name());
                            seat.setAssignedAt(LocalDateTime.now());
                            return reservationSeatRepository.save(seat);
                        })
                        .then())
                .doOnSuccess(v -> log.info("라이브 결제 완료 로컬 반영: reservationId={}", reservationId))
                .then();
    }

    // 선택한 등급에 속한 구역 목록
    public Mono<List<String>> getZonesByGrade(Long scheduleId, String grade) {
        return eventServiceClient.getZonesByGrade(scheduleId, grade);
    }

    /**
     * 등급-구역 검증 후 공유 Redis 좌석 풀(seats:{scheduleId}:{zone})에서 잔여 좌석 번호 목록을 조회한다.
     */
    public Mono<List<String>> getAvailableSeats(Long scheduleId, String grade, String zone) {
        return eventServiceClient.validateGradeAndZone(scheduleId, grade, zone)
                .then(seatPoolService.getAvailableSeats(scheduleId, zone).collectList());
    }

    // 잔여석 존재 여부: 좌석 풀 전체 합산이 0보다 큰지 확인
    public Mono<Boolean> hasRemainingSeats(Long scheduleId) {
        return seatPoolService.getRemainingSeatsTotal(scheduleId)
                .map(total -> total > 0);
    }

    // 라이브 트랙 오픈 여부: 잔여 좌석 + 큐 마감 플래그 둘 다 OK
    public Mono<Boolean> isLiveTrackOpen(Long scheduleId) {
        return hasRemainingSeats(scheduleId)
                .flatMap(hasSeats -> hasSeats
                        ? isLiveTrackClosedByQueue(scheduleId).map(closed -> !closed)
                        : Mono.just(false));
    }

    public Mono<Boolean> isLiveTrackClosedByQueue(Long scheduleId) {
        return redisTemplate.hasKey(RedisKeyGenerator.liveClosedKey(scheduleId));
    }

    private Mono<ReservationSeat> buildAndSaveReservationSeat(Long reservationId, Long scheduleId, String zone, String seatNumber, String status) {
        return eventServiceClient.findSeatByScheduleAndZoneAndNumber(scheduleId, zone, seatNumber)
                .flatMap(seat -> reservationSeatRepository.save(ReservationSeat.builder()
                        .reservationId(reservationId)
                        .seatId(seat.getId())
                        .zone(zone)
                        .seatNumber(seatNumber)
                        .status(status)
                        .createdAt(LocalDateTime.now())
                        .build()));
    }

    private Mono<Boolean> requireTrue(Mono<Boolean> condition, ErrorCode whenFalse) {
        return condition.flatMap(ok -> ok ? Mono.just(true) : Mono.error(new BusinessException(whenFalse)));
    }

    private Mono<Boolean> requireLiveTrackOpen(Mono<Boolean> openCondition) {
        return requireTrue(openCondition, ErrorCode.LIVE_TRACK_CLOSED);
    }
}
