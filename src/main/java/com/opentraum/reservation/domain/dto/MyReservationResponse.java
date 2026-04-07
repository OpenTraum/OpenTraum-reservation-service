package com.opentraum.reservation.domain.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class MyReservationResponse {

    private Long id;
    private Long scheduleId;
    private String grade;
    private Integer quantity;
    private String trackType;
    private String status;
    private List<SeatInfo> seats;
    private LocalDateTime createdAt;

    @Getter
    @Builder
    public static class SeatInfo {
        private String zone;
        private String seatNumber;
        private String status;
    }
}
