package com.opentraum.reservation.domain.service;

import com.opentraum.reservation.domain.client.EventServiceClient;
import com.opentraum.reservation.domain.entity.Reservation;
import com.opentraum.reservation.domain.entity.ReservationStatus;
import com.opentraum.reservation.domain.entity.TrackType;
import com.opentraum.reservation.domain.outbox.service.OutboxService;
import com.opentraum.reservation.domain.queue.service.QueueTokenService;
import com.opentraum.reservation.domain.repository.ReservationRepository;
import com.opentraum.reservation.domain.repository.ReservationSeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveSetOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LotteryTrackServiceTest {

    private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
    private final ReservationSeatRepository reservationSeatRepository = mock(ReservationSeatRepository.class);
    private final EventServiceClient eventServiceClient = mock(EventServiceClient.class);
    private final ReactiveRedisTemplate<String, String> redisTemplate = mock(ReactiveRedisTemplate.class);
    private final ReactiveSetOperations<String, String> setOperations = mock(ReactiveSetOperations.class);
    private final QueueTokenService queueTokenService = mock(QueueTokenService.class);
    private final OutboxService outboxService = mock(OutboxService.class);

    private LotteryTrackService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.add("lottery-paid:30", "20")).thenReturn(Mono.just(1L));
        service = new LotteryTrackService(
                reservationRepository,
                reservationSeatRepository,
                eventServiceClient,
                redisTemplate,
                queueTokenService,
                outboxService);
    }

    @Test
    void paymentCompletedMovesPendingLotteryReservationToPaidPendingSeat() {
        Reservation reservation = reservation(ReservationStatus.PENDING.name(), TrackType.LOTTERY.name());
        when(reservationRepository.findById(10L)).thenReturn(Mono.just(reservation));
        when(reservationRepository.save(reservation)).thenReturn(Mono.just(reservation));

        StepVerifier.create(service.onPaymentCompleted(10L, 20L, 30L))
                .verifyComplete();

        verify(reservationRepository).save(reservation);
        verify(setOperations).add("lottery-paid:30", "20");
        org.assertj.core.api.Assertions.assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.PAID_PENDING_SEAT.name());
    }

    @Test
    void paymentCompletedIsIdempotentForAlreadyPaidPendingSeatReservation() {
        Reservation reservation = reservation(ReservationStatus.PAID_PENDING_SEAT.name(), TrackType.LOTTERY.name());
        when(reservationRepository.findById(10L)).thenReturn(Mono.just(reservation));

        StepVerifier.create(service.onPaymentCompleted(10L, 20L, 30L))
                .verifyComplete();

        verify(reservationRepository, never()).save(any());
        verify(setOperations).add("lottery-paid:30", "20");
    }

    @Test
    void paymentCompletedIgnoresClosedReservationWithoutSideEffects() {
        Reservation reservation = reservation(ReservationStatus.CANCELLED.name(), TrackType.LOTTERY.name());
        when(reservationRepository.findById(10L)).thenReturn(Mono.just(reservation));

        StepVerifier.create(service.onPaymentCompleted(10L, 20L, 30L))
                .verifyComplete();

        verify(reservationRepository, never()).save(any());
        verify(setOperations, never()).add(eq("lottery-paid:30"), eq("20"));
    }

    private static Reservation reservation(String status, String trackType) {
        return Reservation.builder()
                .id(10L)
                .userId(20L)
                .scheduleId(30L)
                .trackType(trackType)
                .status(status)
                .build();
    }
}
