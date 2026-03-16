package com.opentraum.reservation.domain.dto;

public record ReservationRequest(
        Long userId,
        Long eventId,
        Long seatId,
        Long tenantId
) {
}
