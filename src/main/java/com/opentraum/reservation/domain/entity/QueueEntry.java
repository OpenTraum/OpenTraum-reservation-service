package com.opentraum.reservation.domain.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Table("queue_entries")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueEntry {

    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("event_id")
    private Long eventId;

    @Column("tenant_id")
    private Long tenantId;

    private Long position;

    @Builder.Default
    private QueueStatus status = QueueStatus.WAITING;

    @Column("entered_at")
    private LocalDateTime enteredAt;

    public enum QueueStatus {
        WAITING,
        PROCESSING,
        COMPLETED,
        EXPIRED
    }
}
