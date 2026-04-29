package com.opentraum.reservation.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentraum.reservation.global.config.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * seat-assignment 토픽 Consumer.
 *
 * <p>legacy 직접 Kafka 경로에서 전달되는 lottery 결제 완료 신호를 reservation 상태 전이에 연결한다.
 * 실제 좌석 배정은 기존 스케줄러가 결제 마감 이후 {@link LotteryTrackService#assignSeatsToPaidLotteryAndMerge}
 * 로 수행한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatAssignmentConsumer {

    private static final String CONSUMER_GROUP = "reservation-seat-assignment-group";

    private final ObjectMapper objectMapper;
    private final LotteryTrackService lotteryTrackService;

    @KafkaListener(
            topics = KafkaTopics.SEAT_ASSIGNMENT,
            groupId = CONSUMER_GROUP
    )
    public void handleSeatAssignment(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String message = record.value();
        log.info("[Kafka] seat-assignment 수신: topic={}, offset={}, message={}",
                record.topic(), record.offset(), message);

        handle(message)
                .subscribe(
                        ignored -> {
                        },
                        e -> log.error("[Kafka] seat-assignment 처리 실패: offset={}, err={}",
                                record.offset(), e.getMessage(), e),
                        ack::acknowledge);
    }

    private Mono<Void> handle(String message) {
        JsonNode node;
        try {
            node = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("[Kafka] seat-assignment payload 파싱 실패: message={}", message, e);
            return Mono.empty();
        }

        Long reservationId = longOrNull(node, "reservationId", "reservation_id");
        Long userId = longOrNull(node, "userId", "user_id");
        Long scheduleId = longOrNull(node, "scheduleId", "schedule_id");

        if (reservationId == null || userId == null || scheduleId == null) {
            log.warn("[Kafka] seat-assignment 필수 필드 누락: reservationId={}, userId={}, scheduleId={}",
                    reservationId, userId, scheduleId);
            return Mono.empty();
        }

        return lotteryTrackService.onPaymentCompleted(reservationId, userId, scheduleId);
    }

    private static Long longOrNull(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                Long parsed = parseLong(value);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private static Long parseLong(JsonNode value) {
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            String text = value.asText();
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
