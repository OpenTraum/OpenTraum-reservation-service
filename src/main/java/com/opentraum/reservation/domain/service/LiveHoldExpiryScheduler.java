package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.constants.ReservationConstants;
import com.opentraum.reservation.domain.entity.Reservation;
import com.opentraum.reservation.domain.entity.ReservationSeat;
import com.opentraum.reservation.domain.entity.ReservationSeatStatus;
import com.opentraum.reservation.domain.entity.ReservationStatus;
import com.opentraum.reservation.domain.outbox.service.OutboxService;
import com.opentraum.reservation.domain.repository.ReservationRepository;
import com.opentraum.reservation.domain.repository.ReservationSeatRepository;
import com.opentraum.reservation.domain.saga.SeatRef;
import com.opentraum.reservation.global.util.SagaIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 라이브 트랙 좌석 홀드 만료 처리.
 *
 * <p>Wave 3: Redis HOLD 해제와 좌석 풀 반환은 event-service가 소유하므로 여기서는 직접
 * 조작하지 않는다. reservation 로컬에서는 만료된 PENDING 좌석을 CANCELLED로 전이하고,
 * 좌석이 모두 해제되면 reservation 상태를 CANCELLED로 전이한 뒤 Outbox
 * {@code ReservationCancelled}를 발행한다. event-service가 이벤트를 받아 Redis/DB를 회수한다.
 * 부분 만료(예약 내 일부 좌석만 만료)는 {@code ReservationSeatReleased}로 전달한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveHoldExpiryScheduler {

    private final ReservationSeatRepository reservationSeatRepository;
    private final ReservationRepository reservationRepository;
    private final RedissonClient redissonClient;
    private final OutboxService outboxService;

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void releaseExpiredHolds() {
        RLock lock = redissonClient.getLock("scheduler:live-hold-expiry");
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 55, TimeUnit.SECONDS);
            if (!acquired) return;

            LocalDateTime expiryThreshold = LocalDateTime.now().minusMinutes(ReservationConstants.HOLD_MINUTES);
            reservationSeatRepository.findByStatusAndCreatedAtBefore(
                            ReservationSeatStatus.PENDING.name(), expiryThreshold)
                    .flatMap(rs -> reservationRepository.findById(rs.getReservationId())
                            .filter(r -> "LIVE".equals(r.getTrackType()))
                            .flatMap(reservation -> releaseOne(reservation, rs)))
                    .count()
                    .doOnSuccess(count -> {
                        if (count > 0) {
                            log.info("라이브 홀드 만료 처리: {}건 좌석 이벤트 발행", count);
                        }
                    })
                    .block(Duration.ofSeconds(30));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("라이브 홀드 만료 스케줄러 오류", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Mono<Long> releaseOne(Reservation reservation, ReservationSeat seat) {
        String zone = seat.getZone();
        String seatNumber = seat.getSeatNumber();
        seat.setStatus(ReservationSeatStatus.CANCELLED.name());
        return reservationSeatRepository.save(seat)
                .then(Mono.defer(() -> {
                    int newQty = Math.max(0, reservation.getQuantity() - 1);
                    reservation.setQuantity(newQty);
                    reservation.setUpdatedAt(LocalDateTime.now());
                    if (reservation.getSagaId() == null) {
                        reservation.setSagaId(SagaIdGenerator.newSagaId());
                    }
                    if (newQty == 0) {
                        reservation.setStatus(ReservationStatus.CANCELLED.name());
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("reason", "HOLD_EXPIRED");
                        return reservationRepository.save(reservation)
                                .then(outboxService.publish(
                                        reservation.getId(), "reservation", "ReservationCancelled",
                                        reservation.getSagaId(), payload))
                                .thenReturn(1L);
                    }
                    // 부분 만료: 좌석 단위 release 이벤트
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("reason", "HOLD_EXPIRED");
                    payload.put("seats", List.of(SeatRef.builder().zone(zone).seatNumber(seatNumber).build()));
                    return reservationRepository.save(reservation)
                            .then(outboxService.publish(
                                    reservation.getId(), "reservation", "ReservationSeatReleased",
                                    reservation.getSagaId(), payload))
                            .thenReturn(1L);
                }))
                .doOnSuccess(v -> log.debug("홀드 만료 이벤트 발행: reservationId={}, zone={}, seat={}",
                        reservation.getId(), zone, seatNumber));
    }
}
