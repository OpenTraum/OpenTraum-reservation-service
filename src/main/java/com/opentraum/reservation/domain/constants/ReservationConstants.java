package com.opentraum.reservation.domain.constants;

public final class ReservationConstants {

    private ReservationConstants() {
        throw new UnsupportedOperationException("상수 클래스는 인스턴스화할 수 없습니다.");
    }

    public static final int PAYMENT_DEADLINE_MINUTES = 5;
    public static final int HOLD_MINUTES = 10;
    public static final int LOTTERY_OPEN_MINUTES = 30;
    public static final int LOTTERY_ENTRY_CLOSE_MINUTES = 20;
    public static final int LOTTERY_PAYMENT_CLOSE_MINUTES = 15;
    public static final int LOTTERY_MAX_QUANTITY_PER_USER = 2;
    public static final int LIVE_MAX_QUANTITY_PER_USER = 4;

    public static final String MESSAGE_PAYMENT_DEADLINE_LOTTERY =
            "결제를 진행해주세요. " + PAYMENT_DEADLINE_MINUTES + "분 내 결제하지 않으면 자동 취소됩니다.";
    public static final String MESSAGE_PAYMENT_DEADLINE_LIVE =
            "좌석이 최대 " + HOLD_MINUTES + "분간 홀드됩니다. " + PAYMENT_DEADLINE_MINUTES + "분 내 결제 시 홀드가 해제됩니다.";
}
