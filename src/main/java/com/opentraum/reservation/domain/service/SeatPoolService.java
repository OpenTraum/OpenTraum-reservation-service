package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.client.EventServiceClient;
import com.opentraum.reservation.domain.dto.ZoneSeatAssignmentResponse;
import com.opentraum.reservation.global.util.RedisKeyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Redis 기반 좌석 풀 관리 (공유 Redis 직접 접근).
 * Redis Key: seat-pool:{scheduleId}:{zone} (Set of seatNumber)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatPoolService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final EventServiceClient eventServiceClient;

    public Mono<Boolean> selectSeat(Long scheduleId, String zone, String seatNumber) {
        String key = RedisKeyGenerator.seatPoolKey(scheduleId, zone);
        return redisTemplate.opsForSet().remove(key, seatNumber)
                .map(removed -> removed > 0);
    }

    public Mono<Boolean> returnSeat(Long scheduleId, String zone, String seatNumber) {
        String key = RedisKeyGenerator.seatPoolKey(scheduleId, zone);
        return redisTemplate.opsForSet().add(key, seatNumber)
                .map(added -> added > 0);
    }

    public Flux<String> getAvailableSeats(Long scheduleId, String zone) {
        String key = RedisKeyGenerator.seatPoolKey(scheduleId, zone);
        return redisTemplate.opsForSet().members(key);
    }

    public Mono<Long> getRemainingSeats(Long scheduleId, String zone) {
        String key = RedisKeyGenerator.seatPoolKey(scheduleId, zone);
        return redisTemplate.opsForSet().size(key);
    }

    // 등급에 해당하는 모든 구역의 잔여석 합산 (event-service에서 구역 목록 조회)
    public Mono<Long> getRemainingSeatsForGrade(Long scheduleId, String grade) {
        return eventServiceClient.getZonesByGrade(scheduleId, grade)
                .flatMapMany(Flux::fromIterable)
                .flatMap(zone -> getRemainingSeats(scheduleId, zone))
                .reduce(0L, Long::sum);
    }

    // 전체 구역 잔여석 합산 (SCAN 패턴 매칭 후 SCARD)
    public Mono<Long> getRemainingSeatsTotal(Long scheduleId) {
        String pattern = "seat-pool:" + scheduleId + ":*";
        return redisTemplate.keys(pattern)
                .flatMap(key -> redisTemplate.opsForSet().size(key))
                .reduce(0L, Long::sum)
                .defaultIfEmpty(0L);
    }

    // 추첨 할당 쿼터: 등급별 전체 좌석 수의 절반
    public Mono<Long> getRemainingSeatsLottery(Long scheduleId, String grade) {
        return eventServiceClient.countSeatsByScheduleAndGrade(scheduleId, grade)
                .map(total -> total / 2);
    }

    // 등급에 해당하는 모든 구역의 잔여 좌석 목록 (추첨 배정용)
    public Mono<List<ZoneSeatAssignmentResponse>> getAvailableSeatsForGrade(Long scheduleId, String grade) {
        return eventServiceClient.getZonesByGrade(scheduleId, grade)
                .flatMapMany(zones -> Flux.fromIterable(zones)
                        .flatMap(zone -> getAvailableSeats(scheduleId, zone)
                                .map(seatNumber -> new ZoneSeatAssignmentResponse(zone, seatNumber))))
                .collectList();
    }
}
