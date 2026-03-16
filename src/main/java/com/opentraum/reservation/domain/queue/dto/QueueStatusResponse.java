package com.opentraum.reservation.domain.queue.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class QueueStatusResponse {
    private Long position;
    private String status;
    private String token;
    private Integer estimatedWaitMinutes;
    private Long aheadCount;
    private String message;
}
