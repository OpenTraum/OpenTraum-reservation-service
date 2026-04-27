package com.opentraum.reservation.domain.outbox.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Transactional Outbox 레코드.
 *
 * <p>이 테이블은 Debezium이 CDC(binlog)로 감시하여 Kafka 토픽으로 발행한다.
 * 비즈니스 트랜잭션과 동일한 DB 트랜잭션에 포함되어 atomic 발행을 보장한다.
 *
 * <p>payload는 JSON 문자열로 저장하며 공통 필드(saga_id, reservation_id, occurred_at)는
 * {@code OutboxService#publish}에서 자동으로 합성한다.
 */
@Table("outbox_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    private Long id;

    @Column("event_id")
    private String eventId;

    @Column("aggregate_id")
    private Long aggregateId;

    @Column("aggregate_type")
    private String aggregateType;

    @Column("event_type")
    private String eventType;

    @Column("saga_id")
    private String sagaId;

    @Column("trace_id")
    private String traceId;

    private String payload;

    @Column("occurred_at")
    private LocalDateTime occurredAt;
}
