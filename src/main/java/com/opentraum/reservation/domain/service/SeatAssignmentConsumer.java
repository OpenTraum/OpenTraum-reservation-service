package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.global.config.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * seat-assignment 토픽 Consumer 뼈대
 * 실제 좌석 배정 로직 연결은 추후 이슈에서 진행
 */
@Slf4j
@Component
public class SeatAssignmentConsumer {

    @KafkaListener(
            topics = KafkaTopics.SEAT_ASSIGNMENT,
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handleSeatAssignment(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("[Kafka] seat-assignment 수신: topic={}, offset={}, message={}",
                topic, offset, message);

        // TODO: 실제 좌석 배정 로직 연결 (추후 이슈)
    }
}
