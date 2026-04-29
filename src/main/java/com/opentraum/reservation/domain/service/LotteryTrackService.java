package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.client.EventServiceClient;
import com.opentraum.reservation.domain.constants.ReservationConstants;
import com.opentraum.reservation.domain.dto.*;
import com.opentraum.reservation.domain.entity.Reservation;
import com.opentraum.reservation.domain.entity.ReservationSeat;
import com.opentraum.reservation.domain.entity.ReservationSeatStatus;
import com.opentraum.reservation.domain.entity.ReservationStatus;
import com.opentraum.reservation.domain.entity.TrackType;
import com.opentraum.reservation.domain.outbox.service.OutboxService;
import com.opentraum.reservation.domain.repository.ReservationRepository;
import com.opentraum.reservation.domain.repository.ReservationSeatRepository;
import com.opentraum.reservation.domain.queue.service.QueueTokenService;
import com.opentraum.reservation.domain.saga.SeatRef;
import com.opentraum.reservation.global.exception.BusinessException;
import com.opentraum.reservation.global.exception.ErrorCode;
import com.opentraum.reservation.global.util.RedisKeyGenerator;
import com.opentraum.reservation.global.util.SagaIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 추첨 트랙 예매/당첨 확정 흐름.
 *
 * <p>쓰기(좌석 DB 상태·Redis 풀 수정)는 event-service가 단독 소유하고, reservation-service는
 * 읽기 목적으로만 Redis 좌석 풀({@code seats:{scheduleId}:{zone}})을 참조한다.
 * 당첨자 Fisher-Yates 좌석 배정은 reservation 도메인 로직(여러 reservation에 좌석 분배)이므로
 * reservation-service가 수행하고, SOLD 전이/풀 제거는 {@code LotterySeatAssigned} Outbox
 * 이벤트로 event-service에 위임한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryTrackService {

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final EventServiceClient eventServiceClient;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final QueueTokenService queueTokenService;
    private final OutboxService outboxService;

    // 추첨 트랙 예매 요청
    public Mono<ReservationResponse> createLotteryReservation(LotteryReservationRequest request, Long userId, String queueToken) {
        // 0. 대기열 토큰 검증 + 1회성 소비
        return queueTokenService.validateToken(userId, request.getScheduleId(), queueToken)
                .flatMap(valid -> {
                    if (!valid) {
                        return Mono.<Void>error(new BusinessException(ErrorCode.INVALID_QUEUE_TOKEN));
                    }
                    return queueTokenService.consumeToken(userId, request.getScheduleId()).then();
                })
                // 1. 추첨 트랙 시간대 체크
                .then(eventServiceClient.findScheduleOrThrow(request.getScheduleId()))
                .flatMap(schedule -> {
                    if ("LIVE_ONLY".equals(schedule.getTrackPolicy())) {
                        return Mono.error(new BusinessException(ErrorCode.TRACK_NOT_ALLOWED));
                    }
                    LocalDateTime now = LocalDateTime.now();
                    LocalDateTime lotteryOpenAt = schedule.getTicketOpenAt().minusMinutes(ReservationConstants.LOTTERY_OPEN_MINUTES);
                    LocalDateTime lotteryEntryCloseAt = schedule.getTicketOpenAt().minusMinutes(ReservationConstants.LOTTERY_ENTRY_CLOSE_MINUTES);
                    if (now.isBefore(lotteryOpenAt)) {
                        return Mono.error(new BusinessException(ErrorCode.TICKET_NOT_OPENED));
                    }
                    if (!now.isBefore(lotteryEntryCloseAt)) {
                        return Mono.error(new BusinessException(ErrorCode.LOTTERY_ENTRY_CLOSED));
                    }
                    return Mono.just(schedule);
                })
                // 2. 중복 참여 체크
                .flatMap(schedule -> canParticipate(request.getScheduleId(), userId)
                        .flatMap(can -> can ? Mono.just(schedule) : Mono.error(new BusinessException(ErrorCode.ALREADY_PARTICIPATED))))
                .flatMap(schedule -> reservationRepository.existsByUserIdAndScheduleIdAndStatusIn(
                                userId, request.getScheduleId(),
                                "PENDING", "PAID_PENDING_SEAT", "ASSIGNED", "PAID")
                        .flatMap(exists -> exists ? Mono.error(new BusinessException(ErrorCode.ALREADY_PARTICIPATED)) : Mono.just(schedule)))
                // 2-1. 추첨 1인당 최대 2장 제한
                .flatMap(schedule -> reservationRepository.sumLotteryQuantityByUserAndSchedule(request.getScheduleId(), userId)
                        .flatMap(userLotteryQuantity -> {
                            long current = userLotteryQuantity != null ? userLotteryQuantity : 0L;
                            long maxAllowed = ReservationConstants.LOTTERY_MAX_QUANTITY_PER_USER - current;
                            if (request.getQuantity() <= 0 || request.getQuantity() > maxAllowed) {
                                return Mono.error(new BusinessException(ErrorCode.LOTTERY_MAX_QUANTITY_EXCEEDED));
                            }
                            return Mono.just(schedule);
                        }))
                // 3. 추첨 쿼터 체크 (등급 총 좌석수의 절반)
                .flatMap(schedule -> Mono.zip(
                        eventServiceClient.countSeatsByScheduleAndGrade(request.getScheduleId(), request.getGrade())
                                .map(total -> total / 2),
                        reservationRepository.sumLotteryQuantityByScheduleAndGrade(request.getScheduleId(), request.getGrade())
                ))
                .flatMap(tuple -> {
                    long lotteryPoolSize = tuple.getT1();
                    long reserved = tuple.getT2() != null ? tuple.getT2() : 0L;
                    long remaining = lotteryPoolSize - reserved;
                    if (remaining < 0 || request.getQuantity() > remaining) {
                        return Mono.error(new BusinessException(ErrorCode.SEAT_ALREADY_TAKEN));
                    }
                    Reservation reservation = Reservation.builder()
                            .userId(userId)
                            .scheduleId(request.getScheduleId())
                            .grade(request.getGrade())
                            .quantity(request.getQuantity())
                            .trackType(TrackType.LOTTERY.name())
                            .status(ReservationStatus.PENDING.name())
                            .sagaId(SagaIdGenerator.newSagaId())
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    return reservationRepository.save(reservation)
                            .flatMap(saved -> publishLotteryReservationCreated(saved).thenReturn(saved));
                })
                .map(this::createReservationResponse);
    }

    /**
     * Lottery 응모 저장 직후 {@code LotteryReservationCreated} Outbox 이벤트 발행.
     *
     * <p>Lottery 트랙은 응모 시점에 좌석이 배정되지 않아 좌석별 price를 확정할 수 없고,
     * 등급별 단가를 이용해 quantity 만큼 곱한 총액이 필요하다. 현재 event-service 내부 API에
     * 등급 단가 조회 경로가 없으므로 {@code amount}는 null로 발행하고, 추후 등급 단가 조회가
     * 추가되면 {@code quantity * gradePrice}로 채운다.
     */
    private Mono<Void> publishLotteryReservationCreated(Reservation saved) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("user_id", saved.getUserId());
        payload.put("schedule_id", saved.getScheduleId());
        payload.put("track_type", TrackType.LOTTERY.name());
        payload.put("grade", saved.getGrade());
        payload.put("quantity", saved.getQuantity());
        payload.put("amount", null);
        return outboxService.publish(
                        saved.getId(), "reservation", "LotteryReservationCreated",
                        saved.getSagaId(), payload)
                .then();
    }

    private ReservationResponse createReservationResponse(Reservation saved) {
        log.info("추첨 예약 생성: reservationId={}, userId={}, scheduleId={}, grade={}",
                saved.getId(), saved.getUserId(), saved.getScheduleId(), saved.getGrade());
        return ReservationResponse.builder()
                .id(saved.getId())
                .scheduleId(saved.getScheduleId())
                .grade(saved.getGrade())
                .quantity(saved.getQuantity())
                .trackType(saved.getTrackType())
                .status(saved.getStatus())
                .message(ReservationConstants.MESSAGE_PAYMENT_DEADLINE_LOTTERY)
                .paymentDeadline(LocalDateTime.now().plusMinutes(ReservationConstants.PAYMENT_DEADLINE_MINUTES))
                .build();
    }

    // 추첨 결제 완료 처리
    public Mono<Void> onPaymentCompleted(Long reservationId, Long userId, Long scheduleId) {
        String lotteryPaidKey = RedisKeyGenerator.lotteryPaidKey(scheduleId);
        return reservationRepository.findById(reservationId)
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND)))
                .flatMap(reservation -> {
                    if (!TrackType.LOTTERY.name().equals(reservation.getTrackType())) {
                        log.warn("seat-assignment ignored for non-lottery reservation: reservationId={}, track={}",
                                reservationId, reservation.getTrackType());
                        return Mono.<Reservation>empty();
                    }
                    String status = reservation.getStatus();
                    if (ReservationStatus.PAID_PENDING_SEAT.name().equals(status)
                            || ReservationStatus.ASSIGNED.name().equals(status)) {
                        return Mono.just(reservation);
                    }
                    if (ReservationStatus.CANCELLED.name().equals(status)
                            || ReservationStatus.REFUNDED.name().equals(status)) {
                        log.warn("seat-assignment ignored for closed reservation: reservationId={}, status={}",
                                reservationId, status);
                        return Mono.<Reservation>empty();
                    }
                    reservation.setStatus(ReservationStatus.PAID_PENDING_SEAT.name());
                    reservation.setUpdatedAt(LocalDateTime.now());
                    return reservationRepository.save(reservation);
                })
                .flatMap(reservation -> redisTemplate.opsForSet()
                        .add(lotteryPaidKey, userId.toString())
                        .thenReturn(reservation))
                .doOnNext(v -> log.info("추첨 결제 완료: reservationId={}, userId={}", reservationId, userId))
                .then();
    }

    /**
     * Fisher-Yates 기반 추첨 좌석 배정 + 머지.
     *
     * <p>Wave 3 이벤트 전환 설계 롤백: event-service에 Lottery 전용 Fisher-Yates 리스너를 두는
     * 대신, reservation-service가 직접 배정 로직을 수행한다. 등급별 가용 좌석 목록은
     * event-service의 권위 있는 상태가 Redis에 캐싱된 {@code seats:{scheduleId}:{zone}} 집합을
     * <b>읽기 전용</b>으로 조회해 얻는다. 좌석 풀에 대한 <b>쓰기(SOLD 전이 / Redis 제거)</b>는
     * 여전히 event-service만 담당하며, 본 메서드는 reservation 도메인 쪽 상태(reservation_seats
     * ASSIGNED, reservation.status=ASSIGNED)만 확정한 뒤 {@code LotterySeatAssigned} Outbox를
     * 발행해 event-service에서 batch SOLD를 수행하도록 한다.
     *
     * <p>배정 절차:
     * <ol>
     *   <li>schedule 내 PAID_PENDING_SEAT Lottery 예약 수집 (createdAt 순 정렬)</li>
     *   <li>grade 별로 그룹핑</li>
     *   <li>grade 당: event-service REST로 zone 목록 획득 → 각 zone의 Redis AVAILABLE
     *       좌석 번호 조회 → 하나의 리스트로 합침</li>
     *   <li>Fisher-Yates shuffle 후 quantity 만큼 slice 해서 각 reservation에 분배</li>
     *   <li>reservation_seats 저장(ASSIGNED) + reservation.status=ASSIGNED 전이</li>
     *   <li>reservation 단위로 {@code LotterySeatAssigned} Outbox 발행
     *       (payload.seats 포함). event-service가 수신해 SOLD 확정.</li>
     * </ol>
     */
    public Mono<Void> assignSeatsToPaidLotteryAndMerge(Long scheduleId) {
        return reservationRepository.findByScheduleIdAndTrackTypeAndStatus(
                        scheduleId, TrackType.LOTTERY.name(), ReservationStatus.PAID_PENDING_SEAT.name())
                .collectList()
                .flatMap(reservations -> {
                    if (reservations.isEmpty()) {
                        return Mono.<Void>empty();
                    }
                    reservations.sort(Comparator.comparing(Reservation::getId));
                    Map<String, List<Reservation>> byGrade = reservations.stream()
                            .collect(Collectors.groupingBy(Reservation::getGrade));
                    return Flux.fromIterable(byGrade.entrySet())
                            .concatMap(entry -> assignGrade(scheduleId, entry.getKey(), entry.getValue()))
                            .then();
                })
                .doOnSuccess(v -> log.info("추첨 좌석 배정 완료 (Fisher-Yates): scheduleId={}", scheduleId));
    }

    private Mono<Void> assignGrade(Long scheduleId, String grade, List<Reservation> reservations) {
        return fetchAvailableSeatsForGrade(scheduleId, grade)
                .flatMap(seatList -> {
                    fisherYatesShuffle(seatList);
                    long need = reservations.stream().mapToLong(Reservation::getQuantity).sum();
                    if (need > seatList.size()) {
                        return Mono.<Void>error(new BusinessException(ErrorCode.SEAT_ALREADY_TAKEN));
                    }
                    AtomicInteger index = new AtomicInteger(0);
                    return Flux.fromIterable(reservations)
                            .concatMap(r -> {
                                int start = index.get();
                                int q = r.getQuantity();
                                index.addAndGet(q);
                                List<ZoneSeatAssignmentResponse> slice = new ArrayList<>(
                                        seatList.subList(start, start + q));
                                return assignToReservation(scheduleId, r, slice);
                            })
                            .then();
                });
    }

    /**
     * event-service가 쓰는 Redis 좌석 풀(seats:{scheduleId}:{zone})을 <b>읽기 전용</b>으로 조회.
     * reservation-service는 쓰기 경로로 이 키를 건드리지 않는다.
     */
    private Mono<List<ZoneSeatAssignmentResponse>> fetchAvailableSeatsForGrade(Long scheduleId, String grade) {
        return eventServiceClient.getZonesByGrade(scheduleId, grade)
                .flatMapMany(Flux::fromIterable)
                .concatMap(zone -> redisTemplate.opsForSet()
                        .members(seatsPoolKey(scheduleId, zone))
                        .map(seatNumber -> new ZoneSeatAssignmentResponse(zone, seatNumber)))
                .collectList();
    }

    /**
     * event-service RedisKeyGenerator.seatsKey 와 동일한 포맷.
     * 공용 Redis를 공유하므로 키 네이밍이 깨지면 안 된다.
     */
    private static String seatsPoolKey(Long scheduleId, String zone) {
        return String.format("seats:%d:%s", scheduleId, zone);
    }

    private Mono<Void> assignToReservation(Long scheduleId, Reservation reservation,
                                           List<ZoneSeatAssignmentResponse> slice) {
        if (slice.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(slice)
                .concatMap(a -> eventServiceClient.findSeatByScheduleAndZoneAndNumber(
                                scheduleId, a.getZone(), a.getSeatNumber())
                        .flatMap(seatInfo -> reservationSeatRepository.save(ReservationSeat.builder()
                                .reservationId(reservation.getId())
                                .seatId(seatInfo.getId())
                                .zone(a.getZone())
                                .seatNumber(a.getSeatNumber())
                                .status(ReservationSeatStatus.ASSIGNED.name())
                                .assignedAt(LocalDateTime.now())
                                .createdAt(LocalDateTime.now())
                                .build())))
                .then(Mono.defer(() -> {
                    reservation.setQuantity(slice.size());
                    reservation.setStatus(ReservationStatus.ASSIGNED.name());
                    reservation.setUpdatedAt(LocalDateTime.now());
                    if (reservation.getSagaId() == null) {
                        reservation.setSagaId(SagaIdGenerator.newSagaId());
                    }
                    return reservationRepository.save(reservation);
                }))
                .flatMap(saved -> publishLotterySeatAssigned(saved, slice))
                .then();
    }

    /**
     * Lottery 배정 결과 Outbox 발행.
     *
     * <p>event-service {@code ReservationEventListener}가 이를 받아 좌석 DB를 SOLD로 확정하고
     * Redis 좌석 풀을 제거한다. aggregate_type="reservation"이 유지되어 기존 Debezium 라우팅을
     * 재사용한다.
     */
    private Mono<Void> publishLotterySeatAssigned(Reservation reservation,
                                                   List<ZoneSeatAssignmentResponse> slice) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("user_id", reservation.getUserId());
        payload.put("schedule_id", reservation.getScheduleId());
        payload.put("track_type", TrackType.LOTTERY.name());
        payload.put("grade", reservation.getGrade());
        payload.put("seats", slice.stream()
                .map(a -> SeatRef.builder().zone(a.getZone()).seatNumber(a.getSeatNumber()).build())
                .toList());
        return outboxService.publish(
                        reservation.getId(), "reservation", "LotterySeatAssigned",
                        reservation.getSagaId(), payload)
                .then();
    }

    private static void fisherYatesShuffle(List<ZoneSeatAssignmentResponse> list) {
        for (int i = list.size() - 1; i >= 1; i--) {
            int j = ThreadLocalRandom.current().nextInt(i + 1);
            ZoneSeatAssignmentResponse tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    // 양 트랙 중복 참여 체크
    public Mono<Boolean> canParticipate(Long scheduleId, Long userId) {
        String lotteryPaidKey = RedisKeyGenerator.lotteryPaidKey(scheduleId);
        return redisTemplate.opsForSet()
                .isMember(lotteryPaidKey, userId.toString())
                .map(isMember -> !isMember);
    }

    // 추첨 결제 마감 시각 경과 여부
    public Mono<Boolean> isLotteryPaymentDeadlinePassed(Long scheduleId) {
        return eventServiceClient.findScheduleOrThrow(scheduleId)
                .map(schedule -> !LocalDateTime.now().isBefore(
                        schedule.getTicketOpenAt().minusMinutes(ReservationConstants.LOTTERY_PAYMENT_CLOSE_MINUTES)));
    }

    // 추첨 예약 단건 결과 조회
    public Mono<LotteryResultResponse> getLotteryReservationResult(Long reservationId, Long userId) {
        return reservationRepository.findById(reservationId)
                .filter(r -> TrackType.LOTTERY.name().equals(r.getTrackType()) && r.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND)))
                .flatMap(this::toLotteryResultResponse);
    }

    // 해당 회차 내 추첨 예약 목록 (본인 것만)
    public Flux<LotteryResultResponse> getMyLotteryResultsBySchedule(Long scheduleId, Long userId) {
        return reservationRepository.findByUserIdAndScheduleIdAndTrackType(userId, scheduleId, TrackType.LOTTERY.name())
                .flatMap(this::toLotteryResultResponse);
    }

    private Mono<LotteryResultResponse> toLotteryResultResponse(Reservation r) {
        String resultType = toResultType(r.getStatus());
        String message = toResultMessage(r.getStatus());
        LocalDateTime paymentDeadline = ReservationStatus.PENDING.name().equals(r.getStatus())
                ? r.getCreatedAt().plusMinutes(ReservationConstants.PAYMENT_DEADLINE_MINUTES) : null;

        if (ReservationStatus.ASSIGNED.name().equals(r.getStatus())) {
            return reservationSeatRepository.findByReservationId(r.getId())
                    .filter(rs -> ReservationSeatStatus.ASSIGNED.name().equals(rs.getStatus()))
                    .map(rs -> LotteryResultResponse.AssignedSeatDto.builder()
                            .zone(rs.getZone())
                            .seatNumber(rs.getSeatNumber())
                            .build())
                    .collectList()
                    .map(seats -> LotteryResultResponse.builder()
                            .id(r.getId())
                            .scheduleId(r.getScheduleId())
                            .grade(r.getGrade())
                            .quantity(r.getQuantity())
                            .status(r.getStatus())
                            .resultType(resultType)
                            .message(message)
                            .paymentDeadline(paymentDeadline)
                            .seats(seats)
                            .build());
        }
        return Mono.just(LotteryResultResponse.builder()
                .id(r.getId())
                .scheduleId(r.getScheduleId())
                .grade(r.getGrade())
                .quantity(r.getQuantity())
                .status(r.getStatus())
                .resultType(resultType)
                .message(message)
                .paymentDeadline(paymentDeadline)
                .seats(null)
                .build());
    }

    private static String toResultType(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING" -> "PAYMENT_PENDING";
            case "PAID_PENDING_SEAT" -> "WON";
            case "ASSIGNED" -> "ASSIGNED";
            case "CANCELLED", "REFUNDED" -> "LOST";
            default -> status;
        };
    }

    private static String toResultMessage(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING" -> "결제 대기 중입니다. 5분 내 결제해 주세요.";
            case "PAID_PENDING_SEAT" -> "당첨되었습니다. 좌석 배정 후 안내됩니다.";
            case "ASSIGNED" -> "좌석 배정이 완료되었습니다.";
            case "CANCELLED" -> "미당첨(결제 기한 초과) 또는 취소되었습니다.";
            case "REFUNDED" -> "환불 처리되었습니다.";
            default -> "";
        };
    }
}
