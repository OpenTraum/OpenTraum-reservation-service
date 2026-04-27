package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.client.EventServiceClient;
import com.opentraum.reservation.domain.constants.ReservationConstants;
import com.opentraum.reservation.domain.entity.ReservationStatus;
import com.opentraum.reservation.domain.entity.TrackType;
import com.opentraum.reservation.domain.outbox.service.OutboxService;
import com.opentraum.reservation.domain.repository.ReservationRepository;
import com.opentraum.reservation.global.util.SagaIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 추첨 결제 마감(티켓 오픈 LOTTERY_PAYMENT_CLOSE_MINUTES분 전) 경과 시,
 * 미결제(PENDING) 추첨 예약을 자동 취소한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LotteryPaymentExpiryScheduler {

    private final EventServiceClient eventServiceClient;
    private final ReservationRepository reservationRepository;
    private final RedissonClient redissonClient;
    private final OutboxService outboxService;
    private final TransactionalOperator txOperator;

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void cancelUnpaidLotteryReservations() {
        RLock lock = redissonClient.getLock("scheduler:lottery-payment-expiry");
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 55, TimeUnit.SECONDS);
            if (!acquired) return;

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime threshold = now.plusMinutes(ReservationConstants.LOTTERY_PAYMENT_CLOSE_MINUTES);
            eventServiceClient.findSchedulesByTicketOpenBefore(threshold.toString(), "COMPLETED")
                    .filter(schedule -> {
                        LocalDateTime paymentCloseAt = schedule.getTicketOpenAt()
                                .minusMinutes(ReservationConstants.LOTTERY_PAYMENT_CLOSE_MINUTES);
                        return !now.isBefore(paymentCloseAt);
                    })
                    .flatMap(schedule -> reservationRepository
                            .findByScheduleIdAndTrackTypeAndStatus(
                                    schedule.getId(), TrackType.LOTTERY.name(), ReservationStatus.PENDING.name())
                            .flatMap(reservation -> {
                                if (reservation.getSagaId() == null) {
                                    reservation.setSagaId(SagaIdGenerator.newSagaId());
                                }
                                reservation.setStatus(ReservationStatus.CANCELLED.name());
                                reservation.setUpdatedAt(now);
                                Map<String, Object> payload = new HashMap<>();
                                payload.put("reason", "LOTTERY_NOT_WON");
                                Mono<Void> work = reservationRepository.save(reservation)
                                        .then(outboxService.publish(
                                                reservation.getId(), "reservation", "ReservationCancelled",
                                                reservation.getSagaId(), payload))
                                        .then();
                                return txOperator.transactional(work)
                                        .doOnSuccess(r -> log.info(
                                                "추첨 미결제 자동 취소: reservationId={}, scheduleId={}, userId={}",
                                                reservation.getId(), reservation.getScheduleId(), reservation.getUserId()));
                            }))
                    .collectList()
                    .block(Duration.ofSeconds(30));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("추첨 결제 마감 스케줄러 오류", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
