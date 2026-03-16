package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.global.util.RedisKeyGenerator;
import com.opentraum.reservation.domain.constants.ReservationConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Redis 기반 좌석 홀드 관리 (공유 Redis 직접 접근).
 * Redis Key: hold:{scheduleId}:{zone}:{seatNumber} → userId (TTL: HOLD_MINUTES)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatHoldService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public Mono<Boolean> holdSeat(Long scheduleId, String zone, String seatNumber, Long userId) {
        String key = RedisKeyGenerator.holdKey(scheduleId, zone, seatNumber);
        Duration ttl = Duration.ofMinutes(ReservationConstants.HOLD_MINUTES);
        return redisTemplate.opsForValue()
                .setIfAbsent(key, userId.toString(), ttl);
    }

    public Mono<Void> releaseHold(Long scheduleId, String zone, String seatNumber) {
        String key = RedisKeyGenerator.holdKey(scheduleId, zone, seatNumber);
        return redisTemplate.delete(key).then();
    }

    public Mono<Long> getHoldOwner(Long scheduleId, String zone, String seatNumber) {
        String key = RedisKeyGenerator.holdKey(scheduleId, zone, seatNumber);
        return redisTemplate.opsForValue().get(key)
                .map(Long::parseLong);
    }

    public Mono<Duration> getRemainingHoldTime(Long scheduleId, String zone, String seatNumber) {
        String key = RedisKeyGenerator.holdKey(scheduleId, zone, seatNumber);
        return redisTemplate.getExpire(key);
    }
}
