package com.opentraum.reservation.domain.controller;

import com.opentraum.reservation.domain.dto.CancellationWindowResponse;
import com.opentraum.reservation.domain.dto.MyReservationResponse;
import com.opentraum.reservation.domain.dto.ReservationResponse;
import com.opentraum.reservation.domain.service.CancellationWindowService;
import com.opentraum.reservation.domain.service.ReservationQueryService;
import com.opentraum.reservation.domain.service.ReservationSagaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@Tag(name = "Reservation", description = "예약 조회/취소 API")
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationSagaService reservationSagaService;
    private final CancellationWindowService cancellationWindowService;
    private final ReservationQueryService reservationQueryService;

    @Operation(
            summary = "결제 대기 중인 예약 조회",
            description = "사용자가 예약 생성 후 페이지 이동/새로고침 등으로 예약ID를 유실한 경우, " +
                    "해당 공연 일정에서 결제 대기 중(PENDING)인 예약을 재조회하여 reservationId를 획득할 수 있습니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "결제 대기 중인 예약 조회 성공",
                    content = @Content(schema = @Schema(implementation = ReservationResponse.class))),
            @ApiResponse(responseCode = "404", description = "해당 공연 일정에 결제 대기 중인 예약이 없음")
    })
    @GetMapping("/pending")
    public Mono<ResponseEntity<ReservationResponse>> getPendingReservation(
            @Parameter(description = "사용자 ID (Gateway에서 주입)", required = true)
            @RequestHeader("X-User-Id") Long userId,
            @Parameter(description = "공연 일정 ID", required = true)
            @RequestParam Long scheduleId) {
        return reservationQueryService.getPendingReservation(userId, scheduleId)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "내 예매 내역 조회", description = "마이페이지용. 트랙 타입, 좌석 배정 정보 포함.")
    @GetMapping("/my")
    public Mono<ResponseEntity<List<MyReservationResponse>>> getMyReservations(
            @RequestHeader("X-User-Id") Long userId) {
        return reservationQueryService.getMyReservations(userId)
                .collectList()
                .map(ResponseEntity::ok);
    }

    @Operation(
            summary = "예약 취소 (SAGA)",
            description = "배정된 또는 배정 대기 중인 예약을 취소합니다. " +
                    "결제 완료 예약은 ReservationRefundRequested를 발행하여 payment-service 환불 SAGA를 트리거합니다. " +
                    "미결제 예약은 ReservationCancelled를 즉시 발행합니다."
    )
    @DeleteMapping("/{reservationId}")
    public Mono<ResponseEntity<Void>> cancelReservation(
            @PathVariable Long reservationId,
            @RequestHeader("X-User-Id") Long userId) {
        return reservationSagaService.userCancel(reservationId, userId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @Operation(summary = "예약 취소 가능 기간 조회")
    @GetMapping("/schedules/{scheduleId}/cancellation-window")
    public Mono<ResponseEntity<CancellationWindowResponse>> getCancellationWindow(
            @PathVariable Long scheduleId) {
        return cancellationWindowService.getCancellationWindow(scheduleId)
                .map(ResponseEntity::ok);
    }
}
