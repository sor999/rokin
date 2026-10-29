package com.robot.fleet.domain.simulation.repository;

import com.robot.fleet.domain.simulation.dto.SimulationDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SimulationRepository {
    private final JdbcTemplate jdbc;

    public void lockRegistry() {
        jdbc.queryForObject("SELECT id FROM simulation_registry_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    public List<SimulationDto> findAll(String fleetId) {
        return jdbc.query("SELECT * FROM simulation_robot WHERE fleet_id = ? ORDER BY robot_id", (rs, n) ->
                new SimulationDto(rs.getString("robot_id"), rs.getString("fleet_id"), rs.getDouble("start_x"),
                        rs.getDouble("start_y"), rs.getDouble("speed"), rs.getString("desired_state"),
                        rs.getLong("config_version"), rs.getString("runtime_state"), rs.getString("error"),
                        rs.getString("runtime_id"), rs.getObject("reported_at", OffsetDateTime.class)), fleetId);
    }

    public Optional<String> findOwner(String robotId) {
        return jdbc.query("SELECT fleet_id FROM simulation_robot WHERE robot_id = ?",
                (rs, n) -> rs.getString(1), robotId).stream().findFirst();
    }

    public boolean hasTelemetry(String robotId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM telemetry_pose WHERE robot_id = ?", Long.class, robotId);
        return count != null && count > 0;
    }

    public void insert(String fleetId, String robotId, double x, double y, double speed) {
        jdbc.update("""
                INSERT INTO simulation_robot(robot_id, fleet_id, start_x, start_y, speed,
                    desired_state, config_version, runtime_state)
                VALUES (?, ?, ?, ?, ?, 'RUNNING', 1, 'STARTING')
                """, robotId, fleetId, x, y, speed);
    }

    public void delete(String robotId) {
        jdbc.update("""
                UPDATE simulation_robot SET desired_state = 'DELETED', config_version = config_version + 1,
                    runtime_state = 'DELETING', error = NULL, reported_at = NULL WHERE robot_id = ?
                """, robotId);
    }

    public void report(String robotId, String state, String error, String runtimeId, OffsetDateTime now) {
        jdbc.update("""
                UPDATE simulation_robot SET runtime_state = ?, error = ?, runtime_id = ?, reported_at = ?
                WHERE robot_id = ?
                """, state, error, runtimeId, now, robotId);
    }
}
