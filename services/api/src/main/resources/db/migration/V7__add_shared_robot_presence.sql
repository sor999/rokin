CREATE TABLE robot_presence (
    fleet_id VARCHAR(128) NOT NULL,
    robot_id VARCHAR(128) NOT NULL,
    runtime_id VARCHAR(36) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (fleet_id, robot_id, runtime_id)
);
CREATE INDEX idx_presence_expiry ON robot_presence (observed_at);
