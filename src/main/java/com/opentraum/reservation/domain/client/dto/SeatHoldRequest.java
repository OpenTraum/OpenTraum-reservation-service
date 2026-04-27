package com.opentraum.reservation.domain.client.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SeatHoldRequest {
    private Long scheduleId;
    private String zone;
    private String seatNumber;
    private Long reservationId;
    private String sagaId;
    private Long userId;
    private String trackType;
    private Long amount;
}
