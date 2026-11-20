CREATE TABLE simulation_distribution_fleet (
    fleet_id VARCHAR(128) PRIMARY KEY,
    activated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE simulation_execution_server (
    runtime_id VARCHAR(36) PRIMARY KEY,
    fleet_id VARCHAR(128) NOT NULL,
    capacity INTEGER NOT NULL CHECK (capacity BETWEEN 1 AND 10000),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'DRAINING', 'RETIRED')),
    lease_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_execution_server_fleet ON simulation_execution_server(fleet_id, state);

CREATE TABLE simulation_robot_assignment (
    robot_id VARCHAR(64) PRIMARY KEY REFERENCES simulation_robot(robot_id),
    fleet_id VARCHAR(128) NOT NULL,
    server_id VARCHAR(36) REFERENCES simulation_execution_server(runtime_id),
    execution_id VARCHAR(36),
    assignment_version BIGINT NOT NULL DEFAULT 0 CHECK (assignment_version >= 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('UNASSIGNED', 'ASSIGNED', 'RELEASING')),
    lease_expires_at TIMESTAMPTZ,
    last_command_at TIMESTAMPTZ,
    CHECK (state = 'UNASSIGNED' OR
        (server_id IS NOT NULL AND execution_id IS NOT NULL AND lease_expires_at IS NOT NULL))
);
CREATE UNIQUE INDEX idx_assignment_execution ON simulation_robot_assignment(execution_id);
CREATE INDEX idx_assignment_owner ON simulation_robot_assignment(fleet_id, server_id);
