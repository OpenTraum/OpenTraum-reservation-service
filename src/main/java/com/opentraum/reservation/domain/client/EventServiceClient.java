package com.opentraum.reservation.domain.client;

import com.opentraum.reservation.domain.client.dto.GradeSeatCount;
import com.opentraum.reservation.domain.client.dto.ScheduleInfo;
import com.opentraum.reservation.domain.client.dto.SeatInfo;
import com.opentraum.reservation.global.exception.BusinessException;
import com.opentraum.reservation.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * event-service(8083) 호출용 WebClient.
 * 스케줄 조회, 좌석 DB 조회/갱신, 등급/구역 검증 등 DB 의존 연산을 담당.
 */
@Slf4j
@Component
public class EventServiceClient {

    private final WebClient webClient;

    public EventServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${opentraum.event-service.url:http://localhost:8083}") String eventServiceUrl) {
        this.webClient = webClientBuilder.baseUrl(eventServiceUrl).build();
    }

    // ───── Schedule ─────

    public Mono<ScheduleInfo> findScheduleOrThrow(Long scheduleId) {
        return webClient.get()
                .uri("/api/v1/internal/schedules/{id}", scheduleId)
                .retrieve()
                .bodyToMono(ScheduleInfo.class)
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)));
    }

    public Flux<ScheduleInfo> findSchedulesByTicketOpenBefore(String threshold, String excludeStatus) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/internal/schedules")
                        .queryParam("ticketOpenBefore", threshold)
                        .queryParam("excludeStatus", excludeStatus)
                        .build())
                .retrieve()
                .bodyToFlux(ScheduleInfo.class);
    }

    public Mono<Void> updateScheduleStatus(Long scheduleId, String status) {
        return webClient.put()
                .uri("/api/v1/internal/schedules/{id}/status?status={status}", scheduleId, status)
                .retrieve()
                .bodyToMono(Void.class);
    }

    // ───── Grade / Zone ─────

    public Mono<List<String>> getZonesByGrade(Long scheduleId, String grade) {
        return webClient.get()
                .uri("/api/v1/internal/schedules/{id}/grades/{grade}/zones", scheduleId, grade)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<String>>() {});
    }

    public Mono<Void> validateGradeAndZone(Long scheduleId, String grade, String zone) {
        return webClient.get()
                .uri("/api/v1/internal/schedules/{id}/grades/{grade}/zones/{zone}/validate",
                        scheduleId, grade, zone)
                .retrieve()
                .bodyToMono(Void.class);
    }

    public Flux<String> getGradesBySchedule(Long scheduleId) {
        return webClient.get()
                .uri("/api/v1/internal/schedules/{id}/grades", scheduleId)
                .retrieve()
                .bodyToFlux(String.class);
    }

    // ───── Seat (DB) ─────

    public Mono<SeatInfo> findSeatByScheduleAndZoneAndNumber(Long scheduleId, String zone, String seatNumber) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/internal/seats")
                        .queryParam("scheduleId", scheduleId)
                        .queryParam("zone", zone)
                        .queryParam("seatNumber", seatNumber)
                        .build())
                .retrieve()
                .bodyToMono(SeatInfo.class)
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.INVALID_INPUT)));
    }

    public Mono<Void> updateSeatStatus(Long scheduleId, String zone, String seatNumber, String status) {
        return webClient.put()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/internal/seats/status")
                        .queryParam("scheduleId", scheduleId)
                        .queryParam("zone", zone)
                        .queryParam("seatNumber", seatNumber)
                        .queryParam("status", status)
                        .build())
                .retrieve()
                .bodyToMono(Void.class);
    }

    public Mono<Long> countSeatsByScheduleAndGrade(Long scheduleId, String grade) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/internal/seats/count")
                        .queryParam("scheduleId", scheduleId)
                        .queryParam("grade", grade)
                        .build())
                .retrieve()
                .bodyToMono(Long.class)
                .defaultIfEmpty(0L);
    }

    public Flux<GradeSeatCount> countSeatsByScheduleGroupByGrade(Long scheduleId) {
        return webClient.get()
                .uri("/api/v1/internal/seats/count-by-grade?scheduleId={id}", scheduleId)
                .retrieve()
                .bodyToFlux(GradeSeatCount.class);
    }

    public Flux<SeatInfo> findSeatsByScheduleAndZoneAndNumbers(Long scheduleId, String zone, List<String> seatNumbers) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/internal/seats/batch")
                        .queryParam("scheduleId", scheduleId)
                        .queryParam("zone", zone)
                        .queryParam("seatNumbers", String.join(",", seatNumbers))
                        .build())
                .retrieve()
                .bodyToFlux(SeatInfo.class);
    }
}
