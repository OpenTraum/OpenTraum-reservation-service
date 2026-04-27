package com.opentraum.reservation.domain.outbox.repository;

import com.opentraum.reservation.domain.outbox.entity.ProcessedEvent;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface ProcessedEventRepository extends ReactiveCrudRepository<ProcessedEvent, String> {

    @Query("SELECT COUNT(*) FROM processed_events WHERE event_id = :eventId AND consumer_group = :consumerGroup")
    Mono<Long> countByEventIdAndConsumerGroup(String eventId, String consumerGroup);

    default Mono<Boolean> existsByEventIdAndConsumerGroup(String eventId, String consumerGroup) {
        return countByEventIdAndConsumerGroup(eventId, consumerGroup).map(c -> c > 0);
    }
}
