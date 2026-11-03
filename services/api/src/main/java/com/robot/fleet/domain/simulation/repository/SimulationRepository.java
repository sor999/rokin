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

    public void lockFleet(String fleetId) {
        jdbc.update("INSERT INTO simulation_fleet_lock(fleet_id) VALUES (?) ON CONFLICT DO NOTHING", fleetId);
        jdbc.queryForObject("SELECT fleet_id FROM simulation_fleet_lock WHERE fleet_id = ? FOR UPDATE", String.class, fleetId);
    }

    public boolean lockRuntimeForReport(String fleetId) {
        boolean postgres = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                connection -> connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
        return !jdbc.query("SELECT fleet_id FROM simulation_fleet_lock WHERE fleet_id = ? "
                + (postgres ? "FOR SHARE" : "FOR UPDATE"), (rs, n) -> rs.getString(1), fleetId).isEmpty();
    }

    private final org.springframework.jdbc.core.RowMapper<SimulationDto> mapper = (rs, n) ->
            new SimulationDto(rs.getString("robot_id"), rs.getString("fleet_id"), rs.getDouble("start_x"),
                    rs.getDouble("start_y"), rs.getDouble("speed"), rs.getString("desired_state"),
                    rs.getLong("config_version"), rs.getString("runtime_state"), rs.getString("error"),
                    rs.getString("runtime_id"), rs.getObject("reported_at", OffsetDateTime.class));

    public List<SimulationDto> findAll(String fleetId) { return findAll(fleetId, false); }

    public List<SimulationDto> findAll(String fleetId, boolean activeOnly) {
        return jdbc.query("SELECT * FROM simulation_robot WHERE fleet_id = ? "
                + (activeOnly ? "AND runtime_state <> 'DELETED' " : "") + "ORDER BY robot_id", mapper, fleetId);
    }

    public Optional<SimulationDto> find(String fleetId, String robotId) {
        return jdbc.query("SELECT * FROM simulation_robot WHERE fleet_id = ? AND robot_id = ?", mapper, fleetId, robotId)
                .stream().findFirst();
    }

    public long countActive(String fleetId) {
        return jdbc.queryForObject("SELECT count(*) FROM simulation_robot WHERE fleet_id = ? AND runtime_state <> 'DELETED'", Long.class, fleetId);
    }

    public Optional<String> findOwner(String robotId) {
        return jdbc.query("SELECT fleet_id FROM simulation_robot WHERE robot_id = ?",
                (rs, n) -> rs.getString(1), robotId).stream().findFirst();
    }

    public boolean hasTelemetry(String robotId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM telemetry_pose WHERE robot_id = ?)
                    OR EXISTS(SELECT 1 FROM telemetry_battery WHERE robot_id = ?)
                    OR EXISTS(SELECT 1 FROM telemetry_status WHERE robot_id = ?)
                    OR EXISTS(SELECT 1 FROM robot_ack WHERE robot_id = ?)
                """, Boolean.class, robotId, robotId, robotId, robotId);
        return Boolean.TRUE.equals(exists);
    }

    public void insert(String fleetId, String robotId, double x, double y, double speed) {
        insert(fleetId, robotId, x, y, speed, false);
    }

    public void insert(String fleetId, String robotId, double x, double y, double speed, boolean seed) {
        jdbc.update("""
                INSERT INTO simulation_robot(robot_id, fleet_id, start_x, start_y, speed,
                    desired_state, config_version, runtime_state)
                VALUES (?, ?, ?, ?, ?, 'RUNNING', 1, 'STARTING')
                """ + (seed ? " ON CONFLICT DO NOTHING" : ""), robotId, fleetId, x, y, speed);
    }

    public void delete(String robotId) {
        jdbc.update("""
                UPDATE simulation_robot SET desired_state = 'DELETED', config_version = config_version + 1,
                    runtime_state = 'DELETING', error = NULL, reported_at = NULL WHERE robot_id = ? AND desired_state <> 'DELETED'
                """, robotId);
    }

    public void report(String fleetId, com.robot.fleet.domain.simulation.dto.SimulationReport report) {
        jdbc.update("""
                UPDATE simulation_robot SET runtime_state = ?, error = ?, runtime_id = ?, reported_at = clock_timestamp()
                WHERE robot_id = ? AND fleet_id = ? AND config_version = ?
                AND ? = (SELECT runtime_id FROM simulation_runtime_session WHERE fleet_id = ? ORDER BY epoch DESC LIMIT 1)
                AND (? = 'FAILED' OR (desired_state = 'DELETED' AND ? = 'DELETED')
                    OR (desired_state = 'RUNNING' AND ? <> 'DELETED'))
                """, report.state(), report.error(), report.runtimeId(), report.robotId(), fleetId, report.version(),
                report.runtimeId(), fleetId, report.state(), report.state(), report.state());
    }
    public OffsetDateTime now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    public Optional<String> currentRuntime(String fleetId) {
        return jdbc.query("SELECT runtime_id FROM simulation_runtime_session WHERE fleet_id = ? ORDER BY epoch DESC LIMIT 1",
                (rs, n) -> rs.getString(1), fleetId).stream().findFirst();
    }

    public boolean runtimeUsed(String runtimeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM simulation_runtime_session WHERE runtime_id = ?)", Boolean.class, runtimeId));
    }

    public void registerRuntime(String fleetId, String runtimeId) {
        jdbc.update("INSERT INTO simulation_runtime_session(runtime_id, fleet_id) VALUES (?, ?)", runtimeId, fleetId);
    }

    public boolean acceptsTelemetry(String fleetId, String robotId, String runtimeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT CASE WHEN EXISTS(SELECT 1 FROM simulation_robot WHERE robot_id = ?)
                THEN EXISTS(SELECT 1 FROM simulation_robot WHERE robot_id = ? AND fleet_id = ?
                    AND desired_state = 'RUNNING' AND ? = (
                        SELECT runtime_id FROM simulation_runtime_session WHERE fleet_id = ? ORDER BY epoch DESC LIMIT 1))
                ELSE ? END
                """, Boolean.class, robotId, robotId, fleetId, runtimeId, fleetId, runtimeId == null));
    }

}
