package com.opentraum.reservation.global.config;

/**
 * Kafka 토픽 상수.
 *
 * <p>Debezium Outbox EventRouter SMT가 aggregate_type 기반으로 라우팅하는 도메인 토픽과
 * 내부 공용 토픽을 중앙 관리한다. 하드코딩된 토픽 문자열은 이 클래스로 교체한다.
 */
public final class KafkaTopics {

    private KafkaTopics() {}

    // Outbox 도메인 토픽 (Debezium EventRouter로 aggregate_type → 토픽 이름 매핑)
    public static final String RESERVATION = "opentraum.reservation";
    public static final String PAYMENT     = "opentraum.payment";
    public static final String EVENT       = "opentraum.event";
    public static final String DLQ         = "opentraum.dlq";

    // 기존(legacy) 토픽 - 점진 이관 예정
    public static final String SEAT_ASSIGNMENT     = "seat-assignment";
    public static final String SEAT_ASSIGNMENT_DLQ = "seat-assignment-dlq";
    public static final String REFUND              = "refund";
}
