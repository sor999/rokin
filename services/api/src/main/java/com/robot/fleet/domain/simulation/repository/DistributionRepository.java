package com.robot.fleet.domain.simulation.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.robot.fleet.domain.simulation.dto.DistributionDtos.*;

@Repository
public class DistributionRepository {
    private final JdbcTemplate jdbc;

    public DistributionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean legacyRunning(String fleet) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM simulation_runtime_session r
                    WHERE r.fleet_id = ? AND r.epoch =
                        (SELECT MAX(epoch) FROM simulation_runtime_session WHERE fleet_id = ?)
                    AND (r.lease_expires_at > clock_timestamp() OR (r.lease_expires_at IS NULL
                        AND EXISTS(SELECT 1 FROM simulation_robot s WHERE s.runtime_id = r.runtime_id
                            AND s.reported_at > clock_timestamp() - INTERVAL '10' SECOND))))
                """, Boolean.class, fleet, fleet));
    }

    public boolean releaseInProgress(String fleet) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM simulation_robot_assignment
                    WHERE fleet_id = ? AND state = 'RELEASING')
                """, Boolean.class, fleet));
    }

    public void activate(String fleet) {
        jdbc.update("""
                INSERT INTO simulation_distribution_fleet(fleet_id) VALUES (?)
                ON CONFLICT DO NOTHING
                """, fleet);
    }

    public boolean used(String fleet) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM simulation_distribution_fleet WHERE fleet_id = ?)
                """, Boolean.class, fleet));
    }

    public Optional<Server> server(String fleet, String runtime) {
        return servers(fleet).stream().filter(server -> server.runtimeId().equals(runtime)).findFirst();
    }

    public List<Server> servers(String fleet) {
        return jdbc.query("""
                SELECT s.runtime_id, s.capacity, s.state,
                    (s.lease_expires_at > clock_timestamp()) AS online,
                    COUNT(a.robot_id) AS assigned_count
                FROM simulation_execution_server s
                LEFT JOIN simulation_robot_assignment a ON a.server_id = s.runtime_id
                    AND a.state <> 'UNASSIGNED'
                WHERE s.fleet_id = ?
                GROUP BY s.runtime_id ORDER BY s.created_at, s.runtime_id
                """, (rs, row) -> new Server(
                rs.getString("runtime_id"), rs.getInt("capacity"), rs.getString("state"),
                rs.getInt("assigned_count"), rs.getBoolean("online")), fleet);
    }

    public void register(String fleet, String runtime, int capacity, long ttl) {
        jdbc.update("""
                INSERT INTO simulation_execution_server(runtime_id, fleet_id, capacity, state, lease_expires_at)
                VALUES (?, ?, ?, 'ACTIVE', clock_timestamp() + (? * INTERVAL '0.001' SECOND))
                """, runtime, fleet, capacity, ttl);
    }

    public boolean runtimeUsed(String runtime) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM simulation_execution_server WHERE runtime_id = ?)
                    OR EXISTS(SELECT 1 FROM simulation_runtime_session WHERE runtime_id = ?)
                """, Boolean.class, runtime, runtime));
    }

    public boolean renew(String fleet, String runtime, int capacity, long ttl) {
        int updated = jdbc.update("""
                UPDATE simulation_execution_server
                SET capacity = ?, lease_expires_at = clock_timestamp() + (? * INTERVAL '0.001' SECOND)
                WHERE fleet_id = ? AND runtime_id = ? AND state <> 'RETIRED'
                    AND lease_expires_at > clock_timestamp()
                """, capacity, ttl, fleet, runtime);
        if (updated == 0) return false;

        jdbc.update("""
                UPDATE simulation_robot_assignment
                SET lease_expires_at = clock_timestamp() + (? * INTERVAL '0.001' SECOND)
                WHERE fleet_id = ? AND server_id = ? AND state <> 'UNASSIGNED'
                    AND lease_expires_at > clock_timestamp()
                """, ttl, fleet, runtime);
        return true;
    }

    public void expire(String fleet) {
        jdbc.update("""
                UPDATE simulation_execution_server SET state = 'RETIRED'
                WHERE fleet_id = ? AND lease_expires_at <= clock_timestamp()
                """, fleet);
        jdbc.update("""
                UPDATE simulation_robot_assignment a
                SET state = 'UNASSIGNED', server_id = NULL, lease_expires_at = NULL
                WHERE a.fleet_id = ? AND a.state <> 'UNASSIGNED' AND
                    (a.lease_expires_at <= clock_timestamp() OR EXISTS(
                        SELECT 1 FROM simulation_execution_server s WHERE s.runtime_id = a.server_id
                            AND s.state = 'RETIRED'))
                """, fleet);
    }

    public void initializeAssignments(String fleet) {
        jdbc.update("""
                INSERT INTO simulation_robot_assignment(robot_id, fleet_id, state)
                SELECT robot_id, fleet_id, 'UNASSIGNED' FROM simulation_robot
                WHERE fleet_id = ? AND desired_state = 'RUNNING'
                ON CONFLICT DO NOTHING
                """, fleet);
    }

    public List<String> unassigned(String fleet) {
        return jdbc.query("""
                SELECT a.robot_id FROM simulation_robot_assignment a
                JOIN simulation_robot s ON s.robot_id = a.robot_id
                WHERE a.fleet_id = ? AND a.state = 'UNASSIGNED' AND s.desired_state = 'RUNNING'
                ORDER BY a.robot_id
                """, (rs, row) -> rs.getString(1), fleet);
    }

    public void assign(String fleet, String robot, String runtime, long ttl) {
        jdbc.update("""
                UPDATE simulation_robot_assignment SET server_id = ?, execution_id = ?,
                    assignment_version = assignment_version + 1, state = 'ASSIGNED',
                    lease_expires_at = clock_timestamp() + (? * INTERVAL '0.001' SECOND),
                    last_command_at = NULL
                WHERE fleet_id = ? AND robot_id = ? AND state = 'UNASSIGNED'
                """, runtime, UUID.randomUUID().toString(), ttl, fleet, robot);
        jdbc.update("""
                UPDATE simulation_robot SET runtime_state = 'STARTING', reported_at = NULL, error = NULL
                WHERE fleet_id = ? AND robot_id = ?
                """, fleet, robot);
    }

    public List<Assignment> assignments(String fleet, String runtime) {
        return jdbc.query("""
                SELECT a.*, s.config_version, s.desired_state, s.start_x, s.start_y, s.speed
                FROM simulation_robot_assignment a JOIN simulation_robot s ON s.robot_id = a.robot_id
                WHERE a.fleet_id = ? AND a.server_id = ? AND a.state <> 'UNASSIGNED'
                    AND a.lease_expires_at > clock_timestamp()
                ORDER BY a.robot_id
                """, (rs, row) -> new Assignment(
                rs.getString("robot_id"), rs.getString("execution_id"), rs.getLong("assignment_version"),
                rs.getString("state"), rs.getLong("config_version"), rs.getString("desired_state"),
                rs.getDouble("start_x"), rs.getDouble("start_y"), rs.getDouble("speed")), fleet, runtime);
    }

    public List<String> idleAssignments(String fleet, String runtime) {
        return jdbc.query("""
                SELECT a.robot_id FROM simulation_robot_assignment a
                JOIN robot_latest l ON l.robot_id = a.robot_id AND l.runtime_id = a.execution_id AND l.kind = 'status'
                JOIN simulation_robot s ON s.robot_id = a.robot_id
                WHERE a.fleet_id = ? AND a.server_id = ? AND a.state = 'ASSIGNED'
                    AND s.desired_state = 'RUNNING' AND l.state = 'idle'
                    AND l.observed_at > clock_timestamp() - INTERVAL '5' SECOND
                    AND (a.last_command_at IS NULL OR
                        (a.last_command_at < clock_timestamp() - INTERVAL '10' SECOND
                        AND l.observed_at > a.last_command_at))
                    AND NOT EXISTS(SELECT 1 FROM command_outbox c WHERE c.fleet_id = a.fleet_id
                        AND c.robot_id = a.robot_id AND c.received_at IS NULL
                        AND c.expires_at > clock_timestamp())
                ORDER BY a.robot_id
                """, (rs, row) -> rs.getString(1), fleet, runtime);
    }

    public void requestRelease(String fleet, String robot) {
        jdbc.update("""
                UPDATE simulation_robot_assignment SET state = 'RELEASING'
                WHERE fleet_id = ? AND robot_id = ? AND state = 'ASSIGNED'
                """, fleet, robot);
    }

    public void releaseDeleted(String fleet) {
        jdbc.update("""
                UPDATE simulation_robot_assignment SET state = 'RELEASING'
                WHERE fleet_id = ? AND state = 'ASSIGNED' AND robot_id IN
                    (SELECT robot_id FROM simulation_robot WHERE desired_state = 'DELETED')
                """, fleet);
        jdbc.update("""
                UPDATE simulation_robot SET runtime_state = 'DELETED', reported_at = clock_timestamp()
                WHERE fleet_id = ? AND desired_state = 'DELETED' AND robot_id IN
                    (SELECT robot_id FROM simulation_robot_assignment WHERE state = 'UNASSIGNED')
                """, fleet);
    }

    public void released(String fleet, String runtime, ReleaseRequest release) {
        jdbc.update("""
                UPDATE simulation_robot_assignment
                SET state = 'UNASSIGNED', server_id = NULL, lease_expires_at = NULL
                WHERE fleet_id = ? AND server_id = ? AND robot_id = ? AND execution_id = ?
                    AND assignment_version = ? AND state = 'RELEASING'
                """, fleet, runtime, release.robotId(), release.executionId(), release.assignmentVersion());
    }

    public boolean drain(String fleet, String runtime) {
        return jdbc.update("""
                UPDATE simulation_execution_server SET state = 'DRAINING'
                WHERE fleet_id = ? AND runtime_id = ? AND state = 'ACTIVE'
                    AND lease_expires_at > clock_timestamp()
                """, fleet, runtime) == 1;
    }
}
