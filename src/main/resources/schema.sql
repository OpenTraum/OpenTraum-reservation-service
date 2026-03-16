CREATE TABLE IF NOT EXISTS reservations (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    schedule_id         BIGINT       NOT NULL,
    tenant_id           BIGINT,
    grade               VARCHAR(10)  NOT NULL,
    track_type          VARCHAR(20)  NOT NULL,
    status              VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    quantity            INT          NOT NULL DEFAULT 1,
    needs_confirm       BOOLEAN      DEFAULT FALSE,
    confirm_deadline    TIMESTAMP,
    created_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, schedule_id)
);

CREATE TABLE IF NOT EXISTS reservation_seats (
    id                  BIGSERIAL PRIMARY KEY,
    reservation_id      BIGINT       NOT NULL REFERENCES reservations(id),
    seat_id             BIGINT,
    seat_number         VARCHAR(20),
    zone                VARCHAR(20),
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    assigned_at         TIMESTAMP,
    created_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_reservation_seats_seat_id
    ON reservation_seats(seat_id) WHERE seat_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_reservations_user ON reservations(user_id);
CREATE INDEX IF NOT EXISTS idx_reservations_schedule ON reservations(schedule_id);
CREATE INDEX IF NOT EXISTS idx_reservations_status ON reservations(status);
CREATE INDEX IF NOT EXISTS idx_reservation_seats_reservation ON reservation_seats(reservation_id);
