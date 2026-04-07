package com.opentraum.reservation.domain.queue.controller;

import com.opentraum.reservation.domain.queue.dto.QueueEntryResponse;
import com.opentraum.reservation.domain.queue.dto.QueueStatusResponse;
import com.opentraum.reservation.domain.queue.service.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Tag(name = "Queue", description = "대기열 관리 API")
@RestController
@RequestMapping("/api/v1/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    @Operation(summary = "대기열 진입")
    @PostMapping("/{scheduleId}/enter")
    public Mono<ResponseEntity<QueueEntryResponse>> enterQueue(
            @PathVariable Long scheduleId,
            @RequestHeader("X-User-Id") Long userId) {
        return queueService.enterQueue(scheduleId, userId).map(ResponseEntity::ok);
    }

    @Operation(summary = "대기열 상태 조회")
    @GetMapping("/{scheduleId}/status")
    public Mono<ResponseEntity<QueueStatusResponse>> getStatus(
            @PathVariable Long scheduleId,
            @RequestHeader("X-User-Id") Long userId) {
        return queueService.getQueueStatus(scheduleId, userId).map(ResponseEntity::ok);
    }

    @Operation(summary = "대기열 이탈")
    @DeleteMapping("/{scheduleId}/leave")
    public Mono<ResponseEntity<Void>> leaveQueue(
            @PathVariable Long scheduleId,
            @RequestHeader("X-User-Id") Long userId) {
        return queueService.leaveQueue(scheduleId, userId)
                .map(success -> ResponseEntity.noContent().<Void>build());
    }

    @Operation(summary = "하트비트 갱신")
    @PostMapping("/{scheduleId}/heartbeat")
    public Mono<ResponseEntity<Void>> heartbeat(
            @PathVariable Long scheduleId,
            @RequestHeader("X-User-Id") Long userId) {
        return queueService.heartbeat(scheduleId, userId)
                .map(success -> ResponseEntity.ok().<Void>build());
    }
}
