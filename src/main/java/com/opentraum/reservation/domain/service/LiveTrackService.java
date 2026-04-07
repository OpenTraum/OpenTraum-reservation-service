package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.client.EventServiceClient;
import com.opentraum.reservation.domain.constants.ReservationConstants;
import com.opentraum.reservation.domain.dto.SeatSelectionRequest;
import com.opentraum.reservation.domain.dto.SeatSelectionResponse;
import com.opentraum.reservation.domain.entity.Reservation;
import com.opentraum.reservation.domain.entity.ReservationSeat;
import com.opentraum.reservation.domain.entity.ReservationSeatStatus;
import com.opentraum.reservation.domain.entity.ReservationStatus;
import com.opentraum.reservation.domain.entity.TrackType;
import com.opentraum.reservation.domain.repository.ReservationRepository;
import com.opentraum.reservation.domain.repository.ReservationSeatRepository;
import com.opentraum.reservation.global.exception.BusinessException;
import com.opentraum.reservation.global.exception.ErrorCode;
import com.opentraum.reservation.global.util.RedisKeyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTrackService {

    private final SeatPoolService seatPoolService;
    private final SeatHoldService seatHoldService;
    private final LotteryTrackService lotteryTrackService;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final EventServiceClient eventServiceClient;
    private final ReactiveRedisTemplate<String, String> redisTemplate;

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
                // 2. 라이브 트랙 오픈 여부 (잔여석 + 마감 플래그)
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
                // 4-1. 추첨 결제 확정분 보호
                .then(requireTrue(
                        seatPoolService.getRemainingSeatsForGrade(scheduleId, request.getGrade())
                                .zipWith(reservationRepository.sumLotteryPaidQuantityByScheduleAndGrade(scheduleId, request.getGrade()))
                                .map(tuple -> tuple.getT1() - tuple.getT2() > 0),
                        ErrorCode.SOLD_OUT))
                // 5. 등급·구역 검증
                .then(eventServiceClient.validateGradeAndZone(scheduleId, request.getGrade(), request.getZone()))
                // 6. 좌석 풀에서 제거 후 7. 홀드 설정
                .then(seatPoolService.selectSeat(scheduleId, request.getZone(), request.getSeatNumber())
                        .flatMap(selected -> {
                            if (!selected) {
                                return Mono.error(new BusinessException(ErrorCode.SEAT_ALREADY_TAKEN));
                            }
                            return seatHoldService.holdSeat(scheduleId, request.getZone(), request.getSeatNumber(), userId);
                        })
                        .flatMap(held -> {
                            if (!held) {
                                return seatPoolService.returnSeat(scheduleId, request.getZone(), request.getSeatNumber())
                                        .then(Mono.<Reservation>error(new BusinessException(ErrorCode.SEAT_ALREADY_TAKEN)));
                            }
                            return Mono.just(true);
                        }))
                // 8. 예약 생성 또는 기존 예약에 좌석 추가
                .flatMap(ignored -> reservationRepository.findFirstByUserIdAndScheduleIdAndTrackType(
                                userId, scheduleId, TrackType.LIVE.name())
                        .flatMap(existing -> addSeatToReservation(existing, request.getZone(), request.getSeatNumber()))
                        .switchIfEmpty(Mono.defer(() ->
                                reservationRepository.existsByUserIdAndScheduleIdAndStatusIn(
                                                userId, scheduleId, "PENDING", "PAID_PENDING_SEAT", "ASSIGNED", "PAID")
                                        .flatMap(exists -> exists
                                                ? Mono.<Reservation>error(new BusinessException(ErrorCode.ALREADY_PARTICIPATED))
                                                : Mono.just(Reservation.builder()
                                                .userId(userId)
                                                .scheduleId(scheduleId)
                                                .grade(request.getGrade())
                                                .quantity(1)
                                                .trackType(TrackType.LIVE.name())
                                                .status(ReservationStatus.PENDING.name())
                                                .createdAt(LocalDateTime.now())
                                                .updatedAt(LocalDateTime.now())
                                                .build()))
                                        .flatMap(newReservation -> reservationRepository.save(newReservation)
                                                .flatMap(newRes -> buildAndSaveReservationSeat(newRes.getId(), scheduleId, request.getZone(), request.getSeatNumber(), ReservationSeatStatus.PENDING.name())
                                                        .thenReturn(newRes)))))
                )
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

    // 좌석 선택 취소 (본인이 홀드한 좌석만 해제 가능)
    public Mono<Void> releaseSeat(Long scheduleId, String zone, String seatNumber, Long userId) {
        return seatHoldService.getHoldOwner(scheduleId, zone, seatNumber)
                .filter(owner -> owner.equals(userId))
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.SEAT_HOLD_NOT_OWNED)))
                .then(seatHoldService.releaseHold(scheduleId, zone, seatNumber))
                .then(seatPoolService.returnSeat(scheduleId, zone, seatNumber))
                .then();
    }

    // 라이브 결제 완료 시 후속 처리
    public Mono<Void> onPaymentCompleted(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .filter(r -> TrackType.LIVE.name().equals(r.getTrackType()))
                .flatMap(reservation -> {
                    Long scheduleId = reservation.getScheduleId();
                    return reservationSeatRepository.findByReservationId(reservationId)
                            .filter(rs -> ReservationSeatStatus.PENDING.name().equals(rs.getStatus()))
                            .flatMap(seat ->
                                    seatHoldService.releaseHold(scheduleId, seat.getZone(), seat.getSeatNumber())
                                            .then(eventServiceClient.updateSeatStatus(scheduleId, seat.getZone(), seat.getSeatNumber(), "SOLD"))
                                            .then(Mono.defer(() -> {
                                                seat.setStatus(ReservationSeatStatus.ASSIGNED.name());
                                                seat.setAssignedAt(LocalDateTime.now());
                                                return reservationSeatRepository.save(seat);
                                            })))
                            .then(Mono.defer(() -> {
                                reservation.setStatus(ReservationStatus.PAID.name());
                                reservation.setUpdatedAt(LocalDateTime.now());
                                return reservationRepository.save(reservation);
                            }));
                })
                .doOnSuccess(v -> log.info("라이브 결제 완료 처리: reservationId={}", reservationId))
                .then();
    }

    // 선택한 등급에 속한 구역 목록
    public Mono<List<String>> getZonesByGrade(Long scheduleId, String grade) {
        return eventServiceClient.getZonesByGrade(scheduleId, grade);
    }

    // 한 구역의 선택 가능 좌석 번호 목록
    public Mono<List<String>> getAvailableSeats(Long scheduleId, String grade, String zone) {
        return eventServiceClient.validateGradeAndZone(scheduleId, grade, zone)
                .then(seatPoolService.getAvailableSeats(scheduleId, zone).collectList());
    }

    // 전체 구역 합쳐 잔여석 1개 이상인지
    public Mono<Boolean> hasRemainingSeats(Long scheduleId) {
        return seatPoolService.getRemainingSeatsTotal(scheduleId)
                .map(total -> total > 0);
    }

    // 라이브 트랙 오픈 여부
    public Mono<Boolean> isLiveTrackOpen(Long scheduleId) {
        return hasRemainingSeats(scheduleId)
                .flatMap(hasSeats -> hasSeats
                        ? isLiveTrackClosedByQueue(scheduleId).map(closed -> !closed)
                        : Mono.just(false));
    }

    public Mono<Boolean> isLiveTrackClosedByQueue(Long scheduleId) {
        return redisTemplate.hasKey(RedisKeyGenerator.liveClosedKey(scheduleId));
    }

    private Mono<Reservation> addSeatToReservation(Reservation reservation, String zone, String seatNumber) {
        return buildAndSaveReservationSeat(reservation.getId(), reservation.getScheduleId(), zone, seatNumber, ReservationSeatStatus.PENDING.name())
                .then(Mono.defer(() -> {
                    reservation.setQuantity(reservation.getQuantity() + 1);
                    reservation.setUpdatedAt(LocalDateTime.now());
                    return reservationRepository.save(reservation);
                }));
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
