CREATE TABLE simulation_registry_lock (id INTEGER PRIMARY KEY);
INSERT INTO simulation_registry_lock (id) VALUES (1);

CREATE TABLE simulation_robot (
    robot_id VARCHAR(64) PRIMARY KEY,
    fleet_id VARCHAR(128) NOT NULL,
    start_x DOUBLE PRECISION NOT NULL,
    start_y DOUBLE PRECISION NOT NULL,
    speed DOUBLE PRECISION NOT NULL CHECK (speed > 0 AND speed <= 10),
    desired_state VARCHAR(16) NOT NULL CHECK (desired_state IN ('RUNNING', 'DELETED')),
    config_version BIGINT NOT NULL,
    runtime_state VARCHAR(16) NOT NULL CHECK (runtime_state IN ('STARTING', 'RUNNING', 'FAILED', 'DELETING', 'DELETED')),
    error VARCHAR(500),
    runtime_id VARCHAR(36),
    reported_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_simulation_fleet ON simulation_robot (fleet_id);
