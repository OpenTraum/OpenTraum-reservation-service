package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.client.EventServiceClient;
import com.opentraum.reservation.domain.client.dto.GradeSeatCount;
import com.opentraum.reservation.domain.constants.ReservationConstants;
import com.opentraum.reservation.domain.dto.*;
import com.opentraum.reservation.domain.entity.Reservation;
import com.opentraum.reservation.domain.entity.ReservationSeat;
import com.opentraum.reservation.domain.entity.ReservationSeatStatus;
import com.opentraum.reservation.domain.entity.ReservationStatus;
import com.opentraum.reservation.domain.entity.TrackType;
import com.opentraum.reservation.domain.repository.ReservationRepository;
import com.opentraum.reservation.domain.repository.ReservationSeatRepository;
import com.opentraum.reservation.domain.queue.service.QueueTokenService;
import com.opentraum.reservation.global.exception.BusinessException;
import com.opentraum.reservation.global.exception.ErrorCode;
import com.opentraum.reservation.global.util.RedisKeyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryTrackService {

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final EventServiceClient eventServiceClient;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final SeatPoolService seatPoolService;
    private final QueueTokenService queueTokenService;

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
                // 3. 추첨 쿼터 체크
                .flatMap(schedule -> Mono.zip(
                        seatPoolService.getRemainingSeatsLottery(request.getScheduleId(), request.getGrade()),
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
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    return reservationRepository.save(reservation);
                })
                .map(this::createReservationResponse);
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
                .flatMap(reservation -> {
                    reservation.setStatus(ReservationStatus.PAID_PENDING_SEAT.name());
                    reservation.setUpdatedAt(LocalDateTime.now());
                    return reservationRepository.save(reservation);
                })
                .then(redisTemplate.opsForSet().add(lotteryPaidKey, userId.toString()))
                .doOnSuccess(v -> log.info("추첨 결제 완료: reservationId={}, userId={}", reservationId, userId))
                .then();
    }

    // Fisher-Yates Shuffle로 추첨 좌석 배정
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
                            .flatMap(entry -> {
                                String grade = entry.getKey();
                                List<Reservation> resList = entry.getValue();
                                return seatPoolService.getAvailableSeatsForGrade(scheduleId, grade)
                                        .flatMap(seatList -> {
                                            fisherYatesShuffle(seatList);
                                            long need = resList.stream().mapToLong(Reservation::getQuantity).sum();
                                            if (need > seatList.size()) {
                                                return Mono.error(new BusinessException(ErrorCode.SEAT_ALREADY_TAKEN));
                                            }
                                            AtomicInteger index = new AtomicInteger(0);
                                            return Flux.fromIterable(resList)
                                                    .concatMap(r -> {
                                                        int start = index.get();
                                                        int q = r.getQuantity();
                                                        index.addAndGet(q);
                                                        List<ZoneSeatAssignmentResponse> slice = seatList.subList(start, start + q);
                                                        return assignSeatsFromList(scheduleId, r, slice)
                                                                .then(updateReservationAssigned(r.getId()));
                                                    })
                                                    .then();
                                        });
                            })
                            .then();
                })
                .doOnSuccess(v -> log.info("추첨 좌석 배정 완료 (Fisher-Yates): scheduleId={}", scheduleId));
    }

    private static void fisherYatesShuffle(List<ZoneSeatAssignmentResponse> list) {
        for (int i = list.size() - 1; i >= 1; i--) {
            int j = ThreadLocalRandom.current().nextInt(i + 1);
            ZoneSeatAssignmentResponse tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    private Mono<Void> assignSeatsFromList(Long scheduleId, Reservation reservation, List<ZoneSeatAssignmentResponse> assignments) {
        if (assignments.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(assignments)
                .flatMap(a -> seatPoolService.selectSeat(scheduleId, a.getZone(), a.getSeatNumber()).thenReturn(a))
                .collectList()
                .flatMap(kept -> batchFetchSeatsAndBuildReservationSeats(scheduleId, reservation.getId(), kept)
                        .flatMap(toSave -> reservationSeatRepository.saveAll(toSave)
                                .then(Flux.fromIterable(kept)
                                        .flatMap(a -> eventServiceClient.updateSeatStatus(scheduleId, a.getZone(), a.getSeatNumber(), "SOLD"))
                                        .then())));
    }

    private Mono<List<ReservationSeat>> batchFetchSeatsAndBuildReservationSeats(
            Long scheduleId, Long reservationId, List<ZoneSeatAssignmentResponse> assignments) {
        if (assignments.isEmpty()) {
            return Mono.just(List.of());
        }
        Map<String, List<String>> zoneToSeatNumbers = assignments.stream()
                .collect(Collectors.groupingBy(ZoneSeatAssignmentResponse::getZone,
                        Collectors.mapping(ZoneSeatAssignmentResponse::getSeatNumber, Collectors.toList())));
        return Flux.fromIterable(zoneToSeatNumbers.entrySet())
                .flatMap(e -> eventServiceClient.findSeatsByScheduleAndZoneAndNumbers(scheduleId, e.getKey(), e.getValue()))
                .collectList()
                .flatMap(seats -> {
                    Map<String, Long> keyToSeatId = seats.stream()
                            .collect(Collectors.toMap(s -> s.getZone() + "_" + s.getSeatNumber(), s -> s.getId()));
                    List<ReservationSeat> toSave = new ArrayList<>();
                    for (ZoneSeatAssignmentResponse a : assignments) {
                        Long seatId = keyToSeatId.get(a.getZone() + "_" + a.getSeatNumber());
                        if (seatId == null) {
                            return Mono.<List<ReservationSeat>>error(new BusinessException(ErrorCode.SEAT_ALREADY_TAKEN));
                        }
                        toSave.add(ReservationSeat.builder()
                                .reservationId(reservationId)
                                .seatId(seatId)
                                .zone(a.getZone())
                                .seatNumber(a.getSeatNumber())
                                .status(ReservationSeatStatus.ASSIGNED.name())
                                .assignedAt(LocalDateTime.now())
                                .createdAt(LocalDateTime.now())
                                .build());
                    }
                    return Mono.just(toSave);
                });
    }

    private Mono<Void> updateReservationAssigned(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .flatMap(r -> reservationSeatRepository.findByReservationId(reservationId)
                        .filter(rs -> ReservationSeatStatus.ASSIGNED.name().equals(rs.getStatus()))
                        .count()
                        .flatMap(count -> {
                            r.setQuantity(count.intValue());
                            r.setStatus(ReservationStatus.ASSIGNED.name());
                            r.setUpdatedAt(LocalDateTime.now());
                            return reservationRepository.save(r);
                        }))
                .then();
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
