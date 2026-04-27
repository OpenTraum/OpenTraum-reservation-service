package com.opentraum.reservation.domain.outbox.service;

import com.opentraum.reservation.domain.outbox.entity.ProcessedEvent;
import com.opentraum.reservation.domain.outbox.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Consumer 측 이벤트 멱등성 보장 서비스.
 *
 * <p>{@code processed_events} 테이블의 {@code event_id} PRIMARY KEY 제약을 이용해
 * at-least-once 재전송을 차단한다.
 *
 * <p>사용 패턴:
 * <pre>
 * idempotencyService.isProcessed(eventId, "reservation-saga-group")
 *     .flatMap(done -> done ? Mono.empty() : handle(event)
 *         .then(idempotencyService.markProcessed(eventId, "reservation-saga-group")));
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final ProcessedEventRepository processedEventRepository;
    private final R2dbcEntityTemplate entityTemplate;

    public Mono<Boolean> isProcessed(String eventId, String consumerGroup) {
        return processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup);
    }

    public Mono<Void> markProcessed(String eventId, String consumerGroup) {
        ProcessedEvent record = ProcessedEvent.builder()
                .eventId(eventId)
                .consumerGroup(consumerGroup)
                .processedAt(LocalDateTime.now())
                .build();

        return entityTemplate.insert(ProcessedEvent.class)
                .using(record)
                .doOnSuccess(saved -> log.debug(
                        "멱등 처리 기록 완료: eventId={}, consumerGroup={}", eventId, consumerGroup))
                .onErrorResume(e -> {
                    log.warn("멱등 처리 기록 실패(중복 가능): eventId={}, consumerGroup={}, error={}",
                            eventId, consumerGroup, e.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}
