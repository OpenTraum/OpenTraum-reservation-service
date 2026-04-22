package com.opentraum.reservation.domain.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentraum.reservation.domain.entity.TrackType;
import com.opentraum.reservation.domain.outbox.service.IdempotencyService;
import com.opentraum.reservation.domain.repository.ReservationRepository;
import com.opentraum.reservation.domain.service.ReservationSagaService;
import com.opentraum.reservation.global.config.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Payment 토픽 Consumer. SAGA Orchestrator 관점에서
 * {@code opentraum.payment}의 결제 상태 이벤트를 수신하여 예약 상태를 전이시킨다.
 *
 * <ul>
 *   <li>PaymentCompleted   → confirmLive / confirmLottery</li>
 *   <li>PaymentFailed      → cancelBySagaFailure (보상)</li>
 *   <li>RefundCompleted    → confirmRefund (사용자 취소 SAGA 종결)</li>
 * </ul>
 *
 * <p>모든 처리는 {@link IdempotencyService}로 at-least-once 재전송을 방어한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private static final String CONSUMER_GROUP = "reservation-saga-group";

    private static final String EVENT_PAYMENT_COMPLETED = "PaymentCompleted";
    private static final String EVENT_PAYMENT_FAILED = "PaymentFailed";
    private static final String EVENT_REFUND_COMPLETED = "RefundCompleted";

    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final ReservationSagaService sagaService;
    private final ReservationRepository reservationRepository;

    @KafkaListener(
            topics = KafkaTopics.PAYMENT,
            groupId = CONSUMER_GROUP
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String eventId = headerString(record, "event_id");
        String eventType = headerString(record, "event_type");
        String sagaIdHeader = headerString(record, "saga_id");

        if (eventType == null) {
            log.warn("[PaymentEventListener] event_type 누락: offset={}", record.offset());
            ack.acknowledge();
            return;
        }

        if (!EVENT_PAYMENT_COMPLETED.equals(eventType)
                && !EVENT_PAYMENT_FAILED.equals(eventType)
                && !EVENT_REFUND_COMPLETED.equals(eventType)) {
            ack.acknowledge();
            return;
        }

        if (eventId == null) {
            log.warn("[PaymentEventListener] event_id 누락: eventType={}, offset={}", eventType, record.offset());
            ack.acknowledge();
            return;
        }

        log.info("[PaymentEventListener] 수신: eventType={}, eventId={}, sagaId={}",
                eventType, eventId, sagaIdHeader);

        idempotencyService.isProcessed(eventId, CONSUMER_GROUP)
                .flatMap(processed -> {
                    if (Boolean.TRUE.equals(processed)) {
                        log.info("[PaymentEventListener] 중복 skip: eventId={}", eventId);
                        return Mono.empty();
                    }
                    return dispatch(eventType, record.value(), sagaIdHeader)
                            .then(idempotencyService.markProcessed(eventId, CONSUMER_GROUP));
                })
                .doOnError(e -> log.error(
                        "[PaymentEventListener] 처리 실패: eventId={}, eventType={}, err={}",
                        eventId, eventType, e.getMessage(), e))
                .doFinally(sig -> ack.acknowledge())
                .subscribe();
    }

    private Mono<Void> dispatch(String eventType, String payload, String sagaIdHeader) {
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.error("[PaymentEventListener] payload 파싱 실패: {}", payload, e);
            return Mono.empty();
        }

        String sagaId = sagaIdHeader != null ? sagaIdHeader : textOrNull(node, "saga_id");
        Long reservationId = longOrNull(node, "reservation_id");
        Long paymentId = longOrNull(node, "payment_id");

        if (reservationId == null) {
            log.warn("[PaymentEventListener] reservation_id 누락: eventType={}", eventType);
            return Mono.empty();
        }

        switch (eventType) {
            case EVENT_PAYMENT_COMPLETED:
                return handlePaymentCompleted(sagaId, reservationId, paymentId, node);
            case EVENT_PAYMENT_FAILED:
                String reason = textOrNull(node, "reason");
                return sagaService.cancelBySagaFailure(
                        sagaId, reservationId, reason != null ? reason : "PAYMENT_FAILED");
            case EVENT_REFUND_COMPLETED:
                return sagaService.confirmRefund(sagaId, reservationId);
            default:
                return Mono.empty();
        }
    }

    private Mono<Void> handlePaymentCompleted(String sagaId, Long reservationId,
                                              Long paymentId, JsonNode node) {
        String trackTypeHint = textOrNull(node, "track_type");
        if (trackTypeHint != null) {
            return routeByTrack(trackTypeHint, sagaId, reservationId, paymentId);
        }
        // payload에 track_type이 없으면 reservation 조회로 보강
        return reservationRepository.findById(reservationId)
                .flatMap(r -> routeByTrack(r.getTrackType(), sagaId, reservationId, paymentId))
                .switchIfEmpty(Mono.fromRunnable(() -> log.warn(
                        "[PaymentEventListener] reservation 미존재로 confirm skip: reservationId={}",
                        reservationId)));
    }

    private Mono<Void> routeByTrack(String trackType, String sagaId, Long reservationId, Long paymentId) {
        if (TrackType.LIVE.name().equals(trackType)) {
            return sagaService.confirmLive(sagaId, reservationId, paymentId);
        }
        if (TrackType.LOTTERY.name().equals(trackType)) {
            return sagaService.confirmLottery(sagaId, reservationId, paymentId);
        }
        log.warn("[PaymentEventListener] 미지원 track_type={}, reservationId={}", trackType, reservationId);
        return Mono.empty();
    }

    private String headerString(ConsumerRecord<?, ?> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asLong();
    }
}
