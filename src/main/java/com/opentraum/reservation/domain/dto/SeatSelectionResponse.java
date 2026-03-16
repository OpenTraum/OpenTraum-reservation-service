package com.opentraum.reservation.domain.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class SeatSelectionResponse {
    private Long scheduleId;
    private String grade;
    private String zone;
    private String seatNumber;
    private LocalDateTime holdExpiresAt;
    private LocalDateTime paymentDeadline;
    private String message;
}
