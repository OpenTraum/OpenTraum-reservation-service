package com.opentraum.reservation.domain.client.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SeatInfo {
    private Long id;
    private Long scheduleId;
    private String zone;
    private String seatNumber;
    private String grade;
    private String status;
}
