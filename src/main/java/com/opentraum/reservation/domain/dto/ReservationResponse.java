package com.opentraum.reservation.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@Schema(description = "예약 정보 응답")
public class ReservationResponse {
    @Schema(description = "예약 ID", example = "1")
    private Long id;

    @Schema(description = "공연 일정 ID", example = "1")
    private Long scheduleId;

    @Schema(description = "좌석 등급 (VIP, R, S, A)", example = "VIP")
    private String grade;

    @Schema(description = "예약 좌석 수량", example = "2")
    private Integer quantity;

    @Schema(description = "배정받은 좌석 번호들 (쉼표 구분)")
    private String seatNumbers;

    @Schema(description = "트랙 타입 (LOTTERY/LIVE)", example = "LIVE")
    private String trackType;

    @Schema(description = "예약 상태 (PENDING/PAID/PAID_PENDING_SEAT/ASSIGNED/CANCELLED/REFUNDED)", example = "PENDING")
    private String status;

    @Schema(description = "메시지 (선택 사항)")
    private String message;

    @Schema(description = "결제 만료 시간 (예약 생성 후 5분)", example = "2026-02-16T15:30:00")
    private LocalDateTime paymentDeadline;
}
