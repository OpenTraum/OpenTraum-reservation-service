package com.opentraum.reservation.domain.queue.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class QueueEntryResponse {
    private Long scheduleId;
    private Long userId;
    private Long position;
    private Integer estimatedWaitMinutes;
    private String message;
}
