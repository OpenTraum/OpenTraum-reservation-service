package com.opentraum.reservation.domain.outbox.repository;

import com.opentraum.reservation.domain.outbox.entity.OutboxEvent;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxRepository extends ReactiveCrudRepository<OutboxEvent, Long> {
}
