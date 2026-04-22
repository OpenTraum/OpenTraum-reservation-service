package com.opentraum.reservation.domain.saga;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SAGA 이벤트 payload의 좌석 식별자.
 *
 * <p>{@code ReservationCreated} 등의 이벤트에 JSON {@code seats[]} 배열로 직렬화되며
 * Jackson이 snake_case({@code seat_number})로 기록한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatRef {

    private String zone;

    @JsonProperty("seat_number")
    private String seatNumber;
}
