package com.opentraum.reservation.domain.controller;

import com.opentraum.reservation.domain.dto.SeatSelectionRequest;
import com.opentraum.reservation.domain.dto.SeatSelectionResponse;
import com.opentraum.reservation.domain.service.LiveTrackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@Tag(name = "Live Track", description = "라이브 트랙 좌석 선택/해제 API")
@RestController
@RequestMapping("/api/v1/live")
@RequiredArgsConstructor
public class LiveTrackController {

    private final LiveTrackService liveTrackService;

    @Operation(summary = "잔여 좌석 조회", description = "선택한 등급·구역 내 잔여 좌석 번호 목록")
    @GetMapping("/{scheduleId}")
    public Mono<ResponseEntity<List<String>>> getAvailableSeats(
            @PathVariable Long scheduleId,
            @RequestParam String grade,
            @RequestParam String zone) {
        return liveTrackService.getAvailableSeats(scheduleId, grade, zone)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "좌석 선택(홀드)", description = "좌석을 선택하여 10분간 홀드합니다")
    @PostMapping("/{scheduleId}")
    public Mono<ResponseEntity<SeatSelectionResponse>> selectSeat(
            @PathVariable Long scheduleId,
            @RequestBody SeatSelectionRequest request,
            @RequestHeader("X-User-Id") Long userId) {
        return liveTrackService.selectSeat(scheduleId, request, userId)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "좌석 홀드 해제")
    @DeleteMapping("/{scheduleId}")
    public Mono<ResponseEntity<Void>> releaseSeat(
            @PathVariable Long scheduleId,
            @RequestParam String zone,
            @RequestParam String seatNumber,
            @RequestHeader("X-User-Id") Long userId) {
        return liveTrackService.releaseSeat(scheduleId, zone, seatNumber, userId)
                .map(v -> ResponseEntity.noContent().<Void>build());
    }

    @Operation(summary = "라이브 트랙 상태 확인")
    @GetMapping("/{scheduleId}/status")
    public Mono<ResponseEntity<Boolean>> checkTrackStatus(@PathVariable Long scheduleId) {
        return liveTrackService.isLiveTrackOpen(scheduleId)
                .map(ResponseEntity::ok);
    }
}
