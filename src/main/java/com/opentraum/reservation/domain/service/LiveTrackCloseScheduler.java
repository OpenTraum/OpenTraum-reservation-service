package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.client.EventServiceClient;
import com.opentraum.reservation.global.util.RedisKeyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 라이브 트랙 마감 조건:
 * 1. 라이브 시작(티켓 오픈) 1시간 경과 시 마감
 * 2. (보조) 대기열 0인 상태가 10분 이상 지속 시 마감. 단, 오픈 후 30분이 지난 뒤에만 적용
 * 추첨 좌석 배정은 라이브 트랙 시작(티켓 오픈) 후 고정 시점에 실행된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveTrackCloseScheduler {

    private static final int LIVE_TRACK_DURATION_MINUTES = 60;
    private static final int QUEUE_ZERO_DURATION_MINUTES = 10;
    private static final int MIN_OPEN_DURATION_MINUTES = 30;
    private static final int LOTTERY_ASSIGNMENT_DELAY_MINUTES_AFTER_OPEN = 60;

    private final EventServiceClient eventServiceClient;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final LotteryTrackService lotteryTrackService;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void checkAndCloseLiveTrackByQueueEmpty() {
        RLock lock = redissonClient.getLock("scheduler:live-track-close");
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 55, TimeUnit.SECONDS);
            if (!acquired) return;

            LocalDateTime now = LocalDateTime.now();
            eventServiceClient.findSchedulesByTicketOpenBefore(now.toString(), "COMPLETED")
                    .flatMap(schedule -> {
                        Long scheduleId = schedule.getId();
                        String queueKey = RedisKeyGenerator.queueKey(scheduleId);
                        String zeroSinceKey = RedisKeyGenerator.queueZeroSinceKey(scheduleId);
                        String closedKey = RedisKeyGenerator.liveClosedKey(scheduleId);
                        long openDurationMin = Duration.between(schedule.getTicketOpenAt(), now).toMinutes();

                        if (openDurationMin >= LIVE_TRACK_DURATION_MINUTES) {
                            return redisTemplate.hasKey(closedKey)
                                    .flatMap(alreadyClosed -> {
                                        if (Boolean.TRUE.equals(alreadyClosed)) {
                                            return markScheduleCompleted(scheduleId).thenReturn(scheduleId);
                                        }
                                        return closeLiveTrack(scheduleId, closedKey, zeroSinceKey)
                                                .then(eventServiceClient.updateScheduleStatus(scheduleId, "CLOSED"))
                                                .then(markScheduleCompleted(scheduleId))
                                                .doOnSuccess(v -> log.info("라이브 트랙 마감(시작 1시간 경과): scheduleId={}", scheduleId))
                                                .thenReturn(scheduleId);
                                    });
                        }

                        return redisTemplate.opsForZSet().size(queueKey)
                                .flatMap(queueSize -> {
                                    if (queueSize != null && queueSize > 0) {
                                        return redisTemplate.delete(zeroSinceKey).thenReturn(scheduleId);
                                    }
                                    return redisTemplate.opsForValue().get(zeroSinceKey)
                                            .switchIfEmpty(Mono.defer(() -> {
                                                long nowMs = System.currentTimeMillis();
                                                return redisTemplate.opsForValue().set(zeroSinceKey, String.valueOf(nowMs))
                                                        .thenReturn(String.valueOf(nowMs));
                                            }))
                                            .flatMap(tsStr -> {
                                                long zeroSince = Long.parseLong(tsStr);
                                                long elapsedMin = (System.currentTimeMillis() - zeroSince) / (60 * 1000);
                                                boolean canClose = elapsedMin >= QUEUE_ZERO_DURATION_MINUTES
                                                        && openDurationMin >= MIN_OPEN_DURATION_MINUTES;
                                                if (canClose) {
                                                    return closeLiveTrack(scheduleId, closedKey, zeroSinceKey)
                                                            .then(eventServiceClient.updateScheduleStatus(scheduleId, "CLOSED"))
                                                            .doOnSuccess(v -> log.info("라이브 트랙 마감(대기열 0 지속 {}분): scheduleId={}", QUEUE_ZERO_DURATION_MINUTES, scheduleId))
                                                            .thenReturn(scheduleId);
                                                }
                                                return Mono.just(scheduleId);
                                            })
                                            .switchIfEmpty(Mono.just(scheduleId));
                                });
                    })
                    .collectList()
                    .block(Duration.ofSeconds(30));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("라이브 트랙 마감 스케줄러 오류", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void assignLotterySeatsAtFixedTimeAfterOpen() {
        RLock lock = redissonClient.getLock("scheduler:lottery-assignment");
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 55, TimeUnit.SECONDS);
            if (!acquired) return;

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime threshold = now.minusMinutes(LOTTERY_ASSIGNMENT_DELAY_MINUTES_AFTER_OPEN);
            eventServiceClient.findSchedulesByTicketOpenBefore(threshold.toString(), "COMPLETED")
                    .flatMap(schedule -> {
                        Long scheduleId = schedule.getId();
                        String lotteryAssignedKey = RedisKeyGenerator.lotteryAssignedKey(scheduleId);
                        return redisTemplate.hasKey(lotteryAssignedKey)
                                .filter(assigned -> Boolean.FALSE.equals(assigned))
                                .flatMap(ignored -> lotteryTrackService.assignSeatsToPaidLotteryAndMerge(scheduleId)
                                        .then(redisTemplate.opsForValue().set(lotteryAssignedKey, "1", Duration.ofDays(1)))
                                        .then(markScheduleCompleted(scheduleId))
                                        .doOnSuccess(v -> log.info("추첨 좌석 배정 완료 + Schedule COMPLETED: scheduleId={}", scheduleId)));
                    })
                    .collectList()
                    .block(Duration.ofSeconds(30));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("추첨 좌석 배정 스케줄러 오류", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Mono<Void> closeLiveTrack(Long scheduleId, String closedKey, String zeroSinceKey) {
        long closedAtMs = System.currentTimeMillis();
        return redisTemplate.opsForValue().set(closedKey, String.valueOf(closedAtMs), Duration.ofDays(2))
                .then(zeroSinceKey != null ? redisTemplate.delete(zeroSinceKey) : Mono.empty())
                .then();
    }

    private Mono<Void> markScheduleCompleted(Long scheduleId) {
        return Mono.zip(
                redisTemplate.hasKey(RedisKeyGenerator.liveClosedKey(scheduleId)),
                redisTemplate.hasKey(RedisKeyGenerator.lotteryAssignedKey(scheduleId))
        ).flatMap(tuple -> {
            boolean liveClosed = Boolean.TRUE.equals(tuple.getT1());
            boolean lotteryAssigned = Boolean.TRUE.equals(tuple.getT2());
            if (liveClosed && lotteryAssigned) {
                return eventServiceClient.updateScheduleStatus(scheduleId, "COMPLETED")
                        .then(cleanupRedisKeys(scheduleId));
            }
            return Mono.empty();
        });
    }

    private Mono<Void> cleanupRedisKeys(Long scheduleId) {
        return redisTemplate.delete(RedisKeyGenerator.liveClosedKey(scheduleId))
                .then(redisTemplate.delete(RedisKeyGenerator.lotteryAssignedKey(scheduleId)))
                .then(redisTemplate.delete(RedisKeyGenerator.queueZeroSinceKey(scheduleId)))
                .then();
    }
}
