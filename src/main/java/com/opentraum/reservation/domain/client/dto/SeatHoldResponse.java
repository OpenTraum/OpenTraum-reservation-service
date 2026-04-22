package com.opentraum.reservation.domain.client.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SeatHoldResponse {
    private Long seatId;
    private String zone;
    private String seatNumber;
}
