package com.opentraum.reservation.global.util;

public class RedisKeyGenerator {

    private RedisKeyGenerator() {
        throw new UnsupportedOperationException("유틸리티 클래스는 인스턴스화할 수 없습니다.");
    }

    public static String queueKey(Long scheduleId) {
        return String.format("queue:%d", scheduleId);
    }

    public static String tokenKey(Long userId, Long scheduleId) {
        return String.format("token:%d:%d", userId, scheduleId);
    }

    public static String heartbeatKey(Long scheduleId, Long userId) {
        return String.format("heartbeat:%d:%d", scheduleId, userId);
    }

    public static String activeKey(Long scheduleId) {
        return String.format("active:%d", scheduleId);
    }

    public static String lotteryPaidKey(Long scheduleId) {
        return String.format("lottery-paid:%d", scheduleId);
    }

    public static String paymentTimerKey(Long reservationId) {
        return String.format("payment-timer:%d", reservationId);
    }

    public static String liveClosedKey(Long scheduleId) {
        return String.format("live-closed:%d", scheduleId);
    }

    public static String queueZeroSinceKey(Long scheduleId) {
        return String.format("queue-zero-since:%d", scheduleId);
    }

    public static String lotteryAssignedKey(Long scheduleId) {
        return String.format("lottery-assigned:%d", scheduleId);
    }

    public static String activeSchedulesKey() {
        return "active-schedules";
    }

    public static String stockKey(Long scheduleId, String grade) {
        return String.format("stock:%d:%s", scheduleId, grade);
    }

    public static String seatPoolKey(Long scheduleId, String zone) {
        return String.format("seat-pool:%d:%s", scheduleId, zone);
    }

    public static String holdKey(Long scheduleId, String zone, String seatNumber) {
        return String.format("hold:%d:%s:%s", scheduleId, zone, seatNumber);
    }
}
