package com.opentraum.reservation.domain.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.opentraum.reservation.domain.dto.QueueStatusResponse;
import com.opentraum.reservation.domain.service.QueueService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    @PostMapping("/enter")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<QueueStatusResponse> enterQueue(
            @RequestParam Long userId,
            @RequestParam Long eventId,
            @RequestParam Long tenantId) {
        return queueService.enterQueue(userId, eventId, tenantId);
    }

    @GetMapping("/position")
    public Mono<QueueStatusResponse> getQueuePosition(
            @RequestParam Long userId,
            @RequestParam Long eventId,
            @RequestParam Long tenantId) {
        return queueService.getQueuePosition(userId, eventId, tenantId);
    }

    @DeleteMapping("/leave")
    public Mono<Boolean> leaveQueue(
            @RequestParam Long userId,
            @RequestParam Long eventId,
            @RequestParam Long tenantId) {
        return queueService.leaveQueue(userId, eventId, tenantId);
    }

    @GetMapping("/size")
    public Mono<Long> getQueueSize(
            @RequestParam Long eventId,
            @RequestParam Long tenantId) {
        return queueService.getQueueSize(eventId, tenantId);
    }
}
