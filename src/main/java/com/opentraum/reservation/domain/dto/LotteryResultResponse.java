package com.opentraum.reservation.domain.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class LotteryResultResponse {
    private Long id;
    private Long scheduleId;
    private String grade;
    private Integer quantity;
    private String status;
    private String resultType;
    private String message;
    private LocalDateTime paymentDeadline;
    private List<AssignedSeatDto> seats;

    @Getter
    @Builder
    public static class AssignedSeatDto {
        private String zone;
        private String seatNumber;
    }
}
