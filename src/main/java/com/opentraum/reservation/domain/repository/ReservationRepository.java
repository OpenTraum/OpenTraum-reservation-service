package com.opentraum.reservation.domain.repository;

import com.opentraum.reservation.domain.dto.GradeReservedSum;
import com.opentraum.reservation.domain.entity.Reservation;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ReservationRepository extends ReactiveCrudRepository<Reservation, Long> {

    Flux<Reservation> findByUserId(Long userId);

    Flux<Reservation> findByUserIdAndScheduleIdAndTrackType(Long userId, Long scheduleId, String trackType);

    Flux<Reservation> findByScheduleIdAndStatus(Long scheduleId, String status);

    Mono<Boolean> existsByUserIdAndScheduleId(Long userId, Long scheduleId);

    Mono<Reservation> findFirstByUserIdAndScheduleIdAndTrackType(Long userId, Long scheduleId, String trackType);

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM reservations " +
           "WHERE schedule_id = :scheduleId AND grade = :grade AND track_type = 'LOTTERY' " +
           "AND status IN ('PENDING', 'PAID_PENDING_SEAT')")
    Mono<Long> sumLotteryQuantityByScheduleAndGrade(Long scheduleId, String grade);

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM reservations " +
           "WHERE schedule_id = :scheduleId AND user_id = :userId AND track_type = 'LOTTERY' " +
           "AND status IN ('PENDING', 'PAID_PENDING_SEAT')")
    Mono<Long> sumLotteryQuantityByUserAndSchedule(Long scheduleId, Long userId);

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM reservations " +
           "WHERE schedule_id = :scheduleId AND user_id = :userId AND track_type = 'LIVE' " +
           "AND status IN ('PENDING', 'PAID_PENDING_SEAT', 'ASSIGNED')")
    Mono<Long> sumLiveQuantityByUserAndSchedule(Long scheduleId, Long userId);

    Flux<Reservation> findByScheduleIdAndTrackTypeAndStatus(Long scheduleId, String trackType, String status);

    @Query("SELECT grade, COALESCE(SUM(quantity), 0) as total FROM reservations " +
           "WHERE schedule_id = :scheduleId AND track_type = 'LOTTERY' " +
           "AND status IN ('PENDING', 'PAID_PENDING_SEAT') GROUP BY grade")
    Flux<GradeReservedSum> findLotteryReservedSumByScheduleIdGroupByGrade(Long scheduleId);

    Mono<Reservation> findFirstByUserIdAndScheduleIdAndStatusOrderByCreatedAtDesc(Long userId, Long scheduleId, String status);

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM reservations " +
           "WHERE schedule_id = :scheduleId AND grade = :grade AND track_type = 'LOTTERY' " +
           "AND status = 'PAID_PENDING_SEAT'")
    Mono<Long> sumLotteryPaidQuantityByScheduleAndGrade(Long scheduleId, String grade);
}
