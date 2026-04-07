package com.opentraum.reservation.domain.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SeatSelectionRequest {
    private Long scheduleId;
    private String grade;
    private String zone;
    private String seatNumber;
}
