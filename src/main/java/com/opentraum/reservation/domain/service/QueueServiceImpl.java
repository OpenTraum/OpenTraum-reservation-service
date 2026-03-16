package com.opentraum.reservation.domain.service;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;

import com.opentraum.reservation.domain.dto.QueueStatusResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueueServiceImpl implements QueueService {

    private final ReactiveStringRedisTemplate redisTemplate;

    private static final String QUEUE_KEY_PREFIX = "queue:event:";
    private static final long ESTIMATED_WAIT_PER_POSITION_SECONDS = 5;

    private String buildQueueKey(Long eventId, Long tenantId) {
        return QUEUE_KEY_PREFIX + tenantId + ":" + eventId;
    }

    private String buildMemberValue(Long userId) {
        return String.valueOf(userId);
    }

    @Override
    public Mono<QueueStatusResponse> enterQueue(Long userId, Long eventId, Long tenantId) {
        String queueKey = buildQueueKey(eventId, tenantId);
        String member = buildMemberValue(userId);
        double score = System.currentTimeMillis();

        return redisTemplate.opsForZSet()
                .addIfAbsent(queueKey, member, score)
                .flatMap(added -> {
                    if (Boolean.TRUE.equals(added)) {
                        log.info("User {} entered queue for event {} (tenant {})", userId, eventId, tenantId);
                    }
                    return getQueuePosition(userId, eventId, tenantId);
                });
    }

    @Override
    public Mono<QueueStatusResponse> getQueuePosition(Long userId, Long eventId, Long tenantId) {
        String queueKey = buildQueueKey(eventId, tenantId);
        String member = buildMemberValue(userId);

        return redisTemplate.opsForZSet()
                .rank(queueKey, member)
                .map(rank -> {
                    long position = rank + 1;
                    long estimatedWait = position * ESTIMATED_WAIT_PER_POSITION_SECONDS;
                    return new QueueStatusResponse(position, estimatedWait);
                })
                .switchIfEmpty(Mono.error(
                        new IllegalArgumentException("User " + userId + " is not in the queue for event " + eventId)));
    }

    @Override
    public Mono<Boolean> leaveQueue(Long userId, Long eventId, Long tenantId) {
        String queueKey = buildQueueKey(eventId, tenantId);
        String member = buildMemberValue(userId);

        return redisTemplate.opsForZSet()
                .remove(queueKey, member)
                .map(removed -> {
                    if (removed > 0) {
                        log.info("User {} left queue for event {} (tenant {})", userId, eventId, tenantId);
                        return true;
                    }
                    return false;
                });
    }

    @Override
    public Mono<Long> getQueueSize(Long eventId, Long tenantId) {
        String queueKey = buildQueueKey(eventId, tenantId);
        return redisTemplate.opsForZSet().size(queueKey);
    }
}
