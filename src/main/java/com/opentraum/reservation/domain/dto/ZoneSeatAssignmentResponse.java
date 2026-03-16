package com.opentraum.reservation.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ZoneSeatAssignmentResponse {
    private String zone;
    private String seatNumber;
}
