package com.opentraum.reservation.domain.controller;

import com.opentraum.reservation.domain.dto.LotteryReservationRequest;
import com.opentraum.reservation.domain.dto.LotteryResultResponse;
import com.opentraum.reservation.domain.dto.ReservationResponse;
import com.opentraum.reservation.domain.service.LotteryTrackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@Tag(name = "Lottery Track", description = "추첨 트랙 예매/결과 조회 API")
@RestController
@RequestMapping("/api/v1/lottery")
@RequiredArgsConstructor
public class LotteryTrackController {

    private final LotteryTrackService lotteryTrackService;

    @Operation(summary = "추첨 예매 생성", description = "대기열 토큰을 소비하여 추첨 예매를 생성합니다")
    @PostMapping("/{scheduleId}")
    public Mono<ResponseEntity<ReservationResponse>> createReservation(
            @RequestBody LotteryReservationRequest request,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-Queue-Token") String queueToken) {
        return lotteryTrackService.createLotteryReservation(request, userId, queueToken)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "추첨 예약 결과 조회 (단건)")
    @GetMapping("/reservations/{reservationId}")
    public Mono<ResponseEntity<LotteryResultResponse>> getLotteryResult(
            @PathVariable Long reservationId,
            @RequestHeader("X-User-Id") Long userId) {
        return lotteryTrackService.getLotteryReservationResult(reservationId, userId)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "추첨 예약 목록 조회", description = "해당 회차 내 내 추첨 예약 목록 (당첨 결과·좌석 포함)")
    @GetMapping("/reservations")
    public Mono<ResponseEntity<List<LotteryResultResponse>>> getMyLotteryResults(
            @RequestParam Long scheduleId,
            @RequestHeader("X-User-Id") Long userId) {
        return lotteryTrackService.getMyLotteryResultsBySchedule(scheduleId, userId)
                .collectList()
                .map(ResponseEntity::ok);
    }
}
