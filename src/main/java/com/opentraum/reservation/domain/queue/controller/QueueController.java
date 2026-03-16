package com.opentraum.reservation.domain.queue.controller;

import com.opentraum.reservation.domain.queue.dto.QueueEntryResponse;
import com.opentraum.reservation.domain.queue.dto.QueueStatusResponse;
import com.opentraum.reservation.domain.queue.service.QueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    @PostMapping("/{scheduleId}/enter")
    public Mono<ResponseEntity<QueueEntryResponse>> enterQueue(
            @PathVariable Long scheduleId,
            @AuthenticationPrincipal Long userId) {
        return queueService.enterQueue(scheduleId, userId).map(ResponseEntity::ok);
    }

    @GetMapping("/{scheduleId}/status")
    public Mono<ResponseEntity<QueueStatusResponse>> getStatus(
            @PathVariable Long scheduleId,
            @AuthenticationPrincipal Long userId) {
        return queueService.getQueueStatus(scheduleId, userId).map(ResponseEntity::ok);
    }

    @DeleteMapping("/{scheduleId}/leave")
    public Mono<ResponseEntity<Void>> leaveQueue(
            @PathVariable Long scheduleId,
            @AuthenticationPrincipal Long userId) {
        return queueService.leaveQueue(scheduleId, userId)
                .map(success -> ResponseEntity.noContent().<Void>build());
    }

    @PostMapping("/{scheduleId}/heartbeat")
    public Mono<ResponseEntity<Void>> heartbeat(
            @PathVariable Long scheduleId,
            @AuthenticationPrincipal Long userId) {
        return queueService.heartbeat(scheduleId, userId)
                .map(success -> ResponseEntity.ok().<Void>build());
    }
}
