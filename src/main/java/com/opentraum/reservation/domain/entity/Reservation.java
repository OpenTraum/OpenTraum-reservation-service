package com.opentraum.reservation.domain.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("reservations")
public class Reservation {

    @Id
    private Long id;

    private Long userId;
    private Long scheduleId;
    private Long tenantId;

    private String grade;
    private Integer quantity;

    private Boolean needsConfirm;
    private LocalDateTime confirmDeadline;

    private String trackType;
    private String status;

    private String sagaId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
