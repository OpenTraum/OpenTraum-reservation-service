package com.opentraum.reservation.domain.queue.service;

import com.opentraum.reservation.domain.queue.config.QueueProperties;
import com.opentraum.reservation.global.util.RedisKeyGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueScheduler {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final QueueTokenService queueTokenService;
    private final QueueProperties queueProperties;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    private RedisScript<String> batchAdmitScript;

    @PostConstruct
    public void init() {
        batchAdmitScript = RedisScript.of(new ClassPathResource("scripts/batch_admit.lua"), String.class);
    }

    /**
     * 배치 입장 처리 (5초마다)
     * Redisson 분산 락으로 단일 인스턴스만 실행
     */
    @Scheduled(fixedDelayString = "${opentraum.queue.scheduler-interval-ms:5000}")
    public void processBatchEntry() {
        RLock lock = redissonClient.getLock("scheduler:batch-entry");
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 4, TimeUnit.SECONDS);
            if (!acquired) return;

            redisTemplate.opsForSet()
                    .members(RedisKeyGenerator.activeSchedulesKey())
                    .flatMap(this::processBatchForSchedule)
                    .collectList()
                    .block(Duration.ofSeconds(4));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("배치 입장 처리 실패", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Mono<Void> processBatchForSchedule(String scheduleIdStr) {
        Long scheduleId = Long.parseLong(scheduleIdStr);
        String activeKey = RedisKeyGenerator.activeKey(scheduleId);
        String queueKey = RedisKeyGenerator.queueKey(scheduleId);
        long now = System.currentTimeMillis();

        return redisTemplate.execute(
                        batchAdmitScript,
                        List.of(activeKey, queueKey),
                        List.of(
                                String.valueOf(queueProperties.getMaxActiveUsers()),
                                String.valueOf(queueProperties.getBatchSize()),
                                String.valueOf(now),
                                String.valueOf(queueProperties.getActiveTimeoutSeconds() * 1000L)
                        ))
                .next()
                .flatMap(result -> {
                    try {
                        Map<String, Object> parsed = objectMapper.readValue(
                                result, new TypeReference<Map<String, Object>>() {});
                        Object admittedRaw = parsed.get("admitted");
                        @SuppressWarnings("unchecked")
                        List<String> admitted = (admittedRaw instanceof List)
                                ? (List<String>) admittedRaw
                                : List.of();
                        int activeCount = ((Number) parsed.get("activeCount")).intValue();
                        int queueSize = ((Number) parsed.get("queueSize")).intValue();

                        if (!admitted.isEmpty()) {
                            log.info("배치 입장: scheduleId={}, admitted={}, active={}, queue={}",
                                    scheduleId, admitted.size(), activeCount, queueSize);
                        }

                        if (queueSize == 0 && activeCount == 0) {
                            return redisTemplate.opsForSet()
                                    .remove(RedisKeyGenerator.activeSchedulesKey(), scheduleIdStr)
                                    .doOnSuccess(v -> log.info("빈 스케줄 정리: scheduleId={}", scheduleId))
                                    .then();
                        }

                        return Flux.fromIterable(admitted)
                                .flatMap(userId -> queueTokenService.issueToken(Long.parseLong(userId), scheduleId)
                                        .onErrorResume(e -> {
                                            log.warn("토큰 발급 실패: userId={}, scheduleId={}", userId, scheduleId, e);
                                            return Mono.empty();
                                        }))
                                .then();
                    } catch (Exception e) {
                        log.error("Lua Script 결과 파싱 실패: scheduleId={}", scheduleId, e);
                        return Mono.empty();
                    }
                })
                .doOnError(e -> log.error("배치 입장 실패: scheduleId={}", scheduleId, e))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Heartbeat 미갱신 사용자 대기열 제거 (10초마다)
     */
    @Scheduled(fixedDelayString = "${opentraum.queue.cleanup-interval-ms:10000}")
    public void cleanupInactiveUsers() {
        RLock lock = redissonClient.getLock("scheduler:cleanup");
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 9, TimeUnit.SECONDS);
            if (!acquired) return;

            redisTemplate.opsForSet()
                    .members(RedisKeyGenerator.activeSchedulesKey())
                    .flatMap(scheduleIdStr -> {
                        Long scheduleId = Long.parseLong(scheduleIdStr);
                        String queueKey = RedisKeyGenerator.queueKey(scheduleId);

                        return redisTemplate.opsForZSet()
                                .range(queueKey, org.springframework.data.domain.Range.closed(0L, -1L))
                                .flatMap(userId -> {
                                    String heartbeatKey = RedisKeyGenerator.heartbeatKey(scheduleId, Long.parseLong(userId));
                                    return redisTemplate.hasKey(heartbeatKey)
                                            .flatMap(hasHeartbeat -> {
                                                if (!hasHeartbeat) {
                                                    return redisTemplate.opsForZSet()
                                                            .remove(queueKey, userId)
                                                            .doOnSuccess(v -> log.info("비활성 사용자 제거: userId={}, scheduleId={}",
                                                                    userId, scheduleId));
                                                }
                                                return Mono.empty();
                                            });
                                });
                    })
                    .collectList()
                    .block(Duration.ofSeconds(9));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("비활성 사용자 정리 실패", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
