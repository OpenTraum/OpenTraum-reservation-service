package com.opentraum.reservation.global.util;

import java.util.UUID;

/**
 * SAGA 상관관계 ID 생성기.
 *
 * <p>Orchestrator가 새 SAGA를 시작할 때 호출한다. 생성된 UUID는
 * {@code reservations.saga_id}, {@code outbox_events.saga_id}, Kafka header의 {@code saga_id}로
 * 전파되어 전체 흐름을 상관관계로 묶는다.
 */
public final class SagaIdGenerator {

    private SagaIdGenerator() {
        throw new UnsupportedOperationException("유틸리티 클래스는 인스턴스화할 수 없습니다.");
    }

    public static String newSagaId() {
        return UUID.randomUUID().toString();
    }
}
