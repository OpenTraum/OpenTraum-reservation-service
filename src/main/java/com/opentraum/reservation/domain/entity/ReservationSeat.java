package com.opentraum.reservation.domain.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("reservation_seats")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationSeat {

    @Id
    private Long id;

    private Long reservationId;
    private Long seatId;
    private String zone;
    private String seatNumber;
    private String status;
    private LocalDateTime assignedAt;
    private LocalDateTime createdAt;
}
