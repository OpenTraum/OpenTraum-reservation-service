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
    // TODO: event-service의 /api/v1/internal/seats 응답에 price 필드가 추가되면 자동 바인딩된다.
    //       현재는 미노출이라 null이며, payment-service는 null일 때 자체 가격 테이블을 조회한다.
    private Integer price;
}
