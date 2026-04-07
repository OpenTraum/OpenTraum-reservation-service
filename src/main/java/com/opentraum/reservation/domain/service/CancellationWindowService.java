package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.client.EventServiceClient;
import com.opentraum.reservation.domain.client.dto.ScheduleInfo;
import com.opentraum.reservation.domain.dto.CancellationWindowResponse;
import com.opentraum.reservation.global.exception.BusinessException;
import com.opentraum.reservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class CancellationWindowService {

    private static final int CANCELLATION_START_HOURS_AFTER_OPEN = 2;
    private static final int CANCELLATION_DURATION_HOURS = 24;

    private final EventServiceClient eventServiceClient;

    public Mono<Void> validateCancellationAllowed(Long scheduleId) {
        return eventServiceClient.findScheduleOrThrow(scheduleId)
                .flatMap(schedule -> {
                    long startMs = toEpochMillis(schedule.getTicketOpenAt()) + (CANCELLATION_START_HOURS_AFTER_OPEN * 3600L * 1000L);
                    long endMs = startMs + (CANCELLATION_DURATION_HOURS * 3600L * 1000L);
                    long nowMs = System.currentTimeMillis();
                    if (nowMs < startMs) {
                        return Mono.<Void>error(new BusinessException(ErrorCode.CANCELLATION_NOT_AVAILABLE));
                    }
                    if (nowMs > endMs) {
                        return Mono.<Void>error(new BusinessException(ErrorCode.CANCELLATION_WINDOW_EXPIRED));
                    }
                    return Mono.empty();
                });
    }

    public Mono<CancellationWindowResponse> getCancellationWindow(Long scheduleId) {
        return eventServiceClient.findScheduleOrThrow(scheduleId)
                .map(this::buildWindowResponse);
    }

    public Mono<Boolean> isCancellationAllowed(Long scheduleId) {
        return getCancellationWindow(scheduleId).map(CancellationWindowResponse::isAllowed);
    }

    private CancellationWindowResponse buildWindowResponse(ScheduleInfo schedule) {
        long startMs = toEpochMillis(schedule.getTicketOpenAt()) + (CANCELLATION_START_HOURS_AFTER_OPEN * 3600L * 1000L);
        long endMs = startMs + (CANCELLATION_DURATION_HOURS * 3600L * 1000L);
        long nowMs = System.currentTimeMillis();
        LocalDateTime windowStart = toLocalDateTime(startMs);
        LocalDateTime windowEnd = toLocalDateTime(endMs);
        boolean allowed = nowMs >= startMs && nowMs <= endMs;
        String message = allowed
                ? "취소 가능 기간입니다."
                : (nowMs < startMs
                        ? "티켓 오픈 2시간 후부터 취소 가능합니다."
                        : "취소 가능 기간이 지났습니다.");
        return CancellationWindowResponse.builder()
                .allowed(allowed)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .message(message)
                .build();
    }

    private static long toEpochMillis(LocalDateTime dateTime) {
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static LocalDateTime toLocalDateTime(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }
}
