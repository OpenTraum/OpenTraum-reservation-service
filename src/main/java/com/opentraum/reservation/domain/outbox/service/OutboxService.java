package com.opentraum.reservation.domain.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentraum.reservation.domain.outbox.entity.OutboxEvent;
import com.opentraum.reservation.domain.outbox.repository.OutboxRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional Outbox 이벤트 발행자.
 *
 * <p>호출 측 비즈니스 트랜잭션과 동일한 트랜잭션 컨텍스트에서 실행되어야 한다.
 * 이 메서드 자체는 DB INSERT만 수행하고, 실제 Kafka 발행은 Debezium이 binlog를 읽어 처리한다.
 *
 * <p>payload에는 이벤트 스키마 합의문에 따라 공통 필드
 * ({@code saga_id}, {@code reservation_id}, {@code occurred_at})를 자동으로 주입한다.
 */
@Slf4j
@Service
public class OutboxService {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public OutboxService(OutboxRepository outboxRepository,
                         ObjectMapper objectMapper,
                         @Autowired(required = false) Tracer tracer) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    /**
     * Outbox 이벤트를 저장한다.
     *
     * @param aggregateId    집계 루트 ID (reservation_id)
     * @param aggregateType  집계 타입 (Debezium 라우팅 키, 예: "reservation")
     * @param eventType      이벤트 타입 이름 (예: "ReservationCreated")
     * @param sagaId         SAGA 상관관계 UUID
     * @param payload        도메인 고유 필드 맵 (공통 필드는 자동 주입)
     * @return 저장된 OutboxEvent
     */
    public Mono<OutboxEvent> publish(
            Long aggregateId,
            String aggregateType,
            String eventType,
            String sagaId,
            Map<String, Object> payload
    ) {
        LocalDateTime now = LocalDateTime.now();
        String traceId = currentTraceId();

        Map<String, Object> enriched = new HashMap<>();
        if (payload != null) {
            enriched.putAll(payload);
        }
        enriched.put("saga_id", sagaId);
        enriched.put("reservation_id", aggregateId);
        enriched.put("occurred_at", now.atOffset(ZoneOffset.UTC).format(ISO_MILLIS));
        if (traceId != null) {
            enriched.put("trace_id", traceId);
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(enriched);
        } catch (JsonProcessingException e) {
            return Mono.error(new IllegalStateException(
                    "Outbox payload JSON 직렬화 실패: eventType=" + eventType, e));
        }

        OutboxEvent event = OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateId(aggregateId)
                .aggregateType(aggregateType)
                .eventType(eventType)
                .sagaId(sagaId)
                .traceId(traceId)
                .payload(payloadJson)
                .occurredAt(now)
                .build();

        return outboxRepository.save(event)
                .doOnSuccess(saved -> log.debug(
                        "Outbox 이벤트 저장 완료: eventId={}, eventType={}, sagaId={}, aggregateId={}, traceId={}",
                        saved.getEventId(), saved.getEventType(), saved.getSagaId(), saved.getAggregateId(), saved.getTraceId()));
    }

    private String currentTraceId() {
        if (tracer == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }
}
