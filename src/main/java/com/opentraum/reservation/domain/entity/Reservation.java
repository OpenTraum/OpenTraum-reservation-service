package com.opentraum.reservation.domain.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Table("reservations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Reservation {

    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("event_id")
    private Long eventId;

    @Column("seat_id")
    private Long seatId;

    @Column("tenant_id")
    private Long tenantId;

    @Builder.Default
    private ReservationStatus status = ReservationStatus.PENDING;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    public enum ReservationStatus {
        PENDING,
        CONFIRMED,
        CANCELLED,
        EXPIRED
    }
}
