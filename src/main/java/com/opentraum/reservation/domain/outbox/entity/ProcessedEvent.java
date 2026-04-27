package com.opentraum.reservation.domain.outbox.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Consumer 멱등 처리 레코드.
 *
 * <p>Kafka Consumer가 이벤트 처리 직전 {@code event_id}를 조회해 이미 처리했는지 확인하고,
 * 처리 완료 후 동일 트랜잭션에서 삽입한다. PRIMARY KEY 충돌로 at-least-once 재전송을 차단한다.
 *
 * <p><b>{@link Persistable} 구현 이유</b>: Spring Data R2DBC 의 {@code save()} 는 기본적으로
 * {@code @Id} 가 non-null 이면 UPDATE 로 동작. 본 엔티티는 {@code eventId} 를 호출자가 채워서
 * save 하므로 Persistable 없이는 affected=0 UPDATE 만 실행되고 INSERT 가 누락된다.
 */
@Table("processed_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent implements Persistable<String> {

    @Id
    @Column("event_id")
    private String eventId;

    @Column("consumer_group")
    private String consumerGroup;

    @Column("processed_at")
    private LocalDateTime processedAt;

    @Transient
    @Builder.Default
    private boolean newEntity = true;

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }
}
