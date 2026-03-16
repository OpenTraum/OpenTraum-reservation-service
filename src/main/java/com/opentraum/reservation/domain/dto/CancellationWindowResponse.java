package com.opentraum.reservation.domain.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class CancellationWindowResponse {
    private final boolean allowed;
    private final LocalDateTime windowStart;
    private final LocalDateTime windowEnd;
    private final String message;
}
