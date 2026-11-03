CREATE TABLE simulation_fleet_lock (fleet_id VARCHAR(128) PRIMARY KEY);
INSERT INTO simulation_fleet_lock(fleet_id)
SELECT fleet_id FROM simulation_robot UNION SELECT fleet_id FROM simulation_runtime_session;
