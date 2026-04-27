package com.opentraum.reservation.domain.repository;

import com.opentraum.reservation.domain.entity.ReservationSeat;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface ReservationSeatRepository extends ReactiveCrudRepository<ReservationSeat, Long> {

    Flux<ReservationSeat> findByReservationId(Long reservationId);

    Flux<ReservationSeat> findByStatusAndCreatedAtBefore(String status, LocalDateTime before);

    @Modifying
    @Query("UPDATE reservation_seats SET status = 'CANCELLED' WHERE reservation_id = :reservationId")
    Mono<Integer> updateStatusToCancelledByReservationId(Long reservationId);

    /**
     * 특정 seat row 를 PENDING → CANCELLED 로 원자적 전이.
     *
     * <p>동시 DELETE 요청 race 방어. 두 요청이 동시에 진입하면 InnoDB row lock 으로 직렬화되고
     * 첫 번째 UPDATE 만 {@code rows=1} 반환, 두 번째는 {@code rows=0} 으로 귀결된다.
     * 호출자는 반환값으로 승자/패자를 판별해 이후 quantity 감소 + outbox 발행을 승자만 수행한다.
     */
    @Modifying
    @Query("UPDATE reservation_seats SET status = 'CANCELLED' WHERE id = :id AND status = 'PENDING'")
    Mono<Integer> tryCancelPending(Long id);
}
