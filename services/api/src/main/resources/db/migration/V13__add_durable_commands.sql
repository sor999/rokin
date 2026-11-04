CREATE TABLE command_sequence (
    fleet_id VARCHAR(128) NOT NULL,
    robot_id VARCHAR(64) NOT NULL,
    sequence BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (fleet_id, robot_id)
);
CREATE TABLE command_outbox (
    cmd_id UUID PRIMARY KEY,
    fleet_id VARCHAR(128) NOT NULL,
    robot_id VARCHAR(64) NOT NULL,
    idempotency_key UUID,
    request_hash VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    sequence BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    received_at TIMESTAMPTZ,
    outcome_unknown BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (fleet_id, idempotency_key),
    UNIQUE (fleet_id, robot_id, sequence)
);
CREATE INDEX idx_command_pending ON command_outbox(next_attempt_at)
    WHERE received_at IS NULL;
CREATE TABLE command_ack_receipt (
    event_id UUID PRIMARY KEY,
    cmd_id UUID NOT NULL REFERENCES command_outbox(cmd_id),
    robot_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('accepted','done','failed')),
    stored_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_command_receipt_command ON command_ack_receipt(cmd_id);
