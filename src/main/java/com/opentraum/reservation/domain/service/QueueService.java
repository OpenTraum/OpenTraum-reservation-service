package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.dto.QueueStatusResponse;

import reactor.core.publisher.Mono;

public interface QueueService {

    Mono<QueueStatusResponse> enterQueue(Long userId, Long eventId, Long tenantId);

    Mono<QueueStatusResponse> getQueuePosition(Long userId, Long eventId, Long tenantId);

    Mono<Boolean> leaveQueue(Long userId, Long eventId, Long tenantId);

    Mono<Long> getQueueSize(Long eventId, Long tenantId);
}
