CREATE TABLE IF NOT EXISTS reservations (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    schedule_id         BIGINT       NOT NULL,
    tenant_id           BIGINT,
    grade               VARCHAR(10)  NOT NULL,
    track_type          VARCHAR(20)  NOT NULL,
    status              VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    quantity            INT          NOT NULL DEFAULT 1,
    needs_confirm       BOOLEAN      DEFAULT FALSE,
    confirm_deadline    TIMESTAMP,
    saga_id             VARCHAR(36),
    created_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, schedule_id)
);

CREATE TABLE IF NOT EXISTS reservation_seats (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id      BIGINT       NOT NULL,
    seat_id             BIGINT,
    seat_number         VARCHAR(20),
    zone                VARCHAR(20),
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    assigned_at         TIMESTAMP,
    created_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reservation_seats_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id)
);

CREATE INDEX IF NOT EXISTS idx_reservation_seats_seat_id
    ON reservation_seats(seat_id);

CREATE INDEX IF NOT EXISTS idx_reservations_user ON reservations(user_id);
CREATE INDEX IF NOT EXISTS idx_reservations_schedule ON reservations(schedule_id);
CREATE INDEX IF NOT EXISTS idx_reservations_status ON reservations(status);
CREATE INDEX IF NOT EXISTS idx_reservation_seats_reservation ON reservation_seats(reservation_id);

CREATE TABLE IF NOT EXISTS outbox_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36) NOT NULL UNIQUE,
    aggregate_id BIGINT NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    saga_id CHAR(36) NOT NULL,
    trace_id CHAR(32),
    payload JSON NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_outbox_aggregate (aggregate_type, aggregate_id),
    INDEX idx_outbox_saga (saga_id),
    INDEX idx_outbox_occurred (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS processed_events (
    event_id CHAR(36) PRIMARY KEY,
    consumer_group VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
