CREATE TABLE IF NOT EXISTS reservations (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    event_id    BIGINT       NOT NULL,
    seat_id     BIGINT       NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_reservation_user_event_seat UNIQUE (user_id, event_id, seat_id)
);

CREATE INDEX IF NOT EXISTS idx_reservations_event_tenant ON reservations (event_id, tenant_id);
CREATE INDEX IF NOT EXISTS idx_reservations_user_event ON reservations (user_id, event_id);
CREATE INDEX IF NOT EXISTS idx_reservations_status ON reservations (status);

CREATE TABLE IF NOT EXISTS queue_entries (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    event_id    BIGINT       NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    position    BIGINT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'WAITING',
    entered_at  TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_queue_user_event UNIQUE (user_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_queue_entries_event_tenant ON queue_entries (event_id, tenant_id);
CREATE INDEX IF NOT EXISTS idx_queue_entries_status ON queue_entries (status);
