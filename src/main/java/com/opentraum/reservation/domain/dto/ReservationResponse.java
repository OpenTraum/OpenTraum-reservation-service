package com.opentraum.reservation.domain.dto;

import java.time.LocalDateTime;

import com.opentraum.reservation.domain.entity.Reservation;

public record ReservationResponse(
        Long id,
        Long userId,
        Long eventId,
        Long seatId,
        Long tenantId,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getUserId(),
                reservation.getEventId(),
                reservation.getSeatId(),
                reservation.getTenantId(),
                reservation.getStatus().name(),
                reservation.getCreatedAt(),
                reservation.getUpdatedAt()
        );
    }
}
