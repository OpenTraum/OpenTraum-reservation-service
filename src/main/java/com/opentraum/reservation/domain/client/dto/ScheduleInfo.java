package com.opentraum.reservation.domain.client.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleInfo {
    private Long id;
    private Long concertId;
    private LocalDateTime ticketOpenAt;
    private String status;
}
