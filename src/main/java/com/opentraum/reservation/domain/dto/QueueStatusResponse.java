package com.opentraum.reservation.domain.dto;

public record QueueStatusResponse(
        Long position,
        Long estimatedWaitTime
) {
}
