package com.opentraum.reservation.domain.queue.service;

import com.opentraum.reservation.domain.queue.config.QueueProperties;
import com.opentraum.reservation.global.util.RedisKeyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueTokenService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final QueueProperties queueProperties;

    public Mono<String> issueToken(Long userId, Long scheduleId) {
        String tokenKey = RedisKeyGenerator.tokenKey(userId, scheduleId);
        String tokenValue = UUID.randomUUID().toString();
        Duration tokenTtl = Duration.ofSeconds(queueProperties.getTokenTtlSeconds());

        return redisTemplate.opsForValue()
                .set(tokenKey, tokenValue, tokenTtl)
                .thenReturn(tokenValue)
                .doOnSuccess(token -> log.info("입장 토큰 발급: userId={}, scheduleId={}", userId, scheduleId));
    }

    public Mono<Boolean> validateToken(Long userId, Long scheduleId, String token) {
        String tokenKey = RedisKeyGenerator.tokenKey(userId, scheduleId);
        return redisTemplate.opsForValue().get(tokenKey)
                .map(storedToken -> storedToken.equals(token))
                .defaultIfEmpty(false);
    }

    public Mono<Boolean> consumeToken(Long userId, Long scheduleId) {
        String tokenKey = RedisKeyGenerator.tokenKey(userId, scheduleId);
        return redisTemplate.delete(tokenKey)
                .map(deleted -> deleted > 0)
                .doOnSuccess(success -> log.info("입장 토큰 소비: userId={}, scheduleId={}", userId, scheduleId));
    }
}
