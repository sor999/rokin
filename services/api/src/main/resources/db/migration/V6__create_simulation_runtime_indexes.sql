CREATE INDEX CONCURRENTLY idx_pose_runtime ON telemetry_pose (robot_id, simulation_runtime_id, timestamp DESC);
CREATE INDEX CONCURRENTLY idx_battery_runtime ON telemetry_battery (robot_id, simulation_runtime_id, timestamp DESC);
CREATE INDEX CONCURRENTLY idx_status_runtime ON telemetry_status (robot_id, simulation_runtime_id, timestamp DESC);
CREATE INDEX CONCURRENTLY idx_ack_robot ON robot_ack (robot_id);
