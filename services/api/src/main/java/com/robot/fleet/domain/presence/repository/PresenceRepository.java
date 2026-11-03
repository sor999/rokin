package com.robot.fleet.domain.presence.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class PresenceRepository {
    public record Expired(String robotId, String runtimeId) { }
    private final JdbcTemplate jdbc;

    private static final String CURRENT_OWNER = """
            (NOT EXISTS (SELECT 1 FROM simulation_robot s WHERE s.robot_id = p.robot_id)
                AND p.runtime_id = ''
             OR EXISTS (SELECT 1 FROM simulation_robot s WHERE s.robot_id = p.robot_id
                AND s.fleet_id = p.fleet_id AND s.desired_state = 'RUNNING'
                AND p.runtime_id = (SELECT r.runtime_id FROM simulation_runtime_session r
                    WHERE r.fleet_id = p.fleet_id ORDER BY r.epoch DESC LIMIT 1)))
            """;

    private static final String READY = """
            (NOT EXISTS (SELECT 1 FROM simulation_robot s WHERE s.robot_id = p.robot_id)
             OR EXISTS (SELECT 1 FROM simulation_robot s WHERE s.robot_id = p.robot_id
                AND s.runtime_id = p.runtime_id AND s.runtime_state = 'RUNNING'
                AND s.reported_at >= CURRENT_TIMESTAMP - INTERVAL '10' SECOND))
            """;

    public boolean observe(String fleet, String robot, String runtime, OffsetDateTime timestamp, long ttl) {
        return jdbc.update("""
                INSERT INTO robot_presence AS p (fleet_id, robot_id, runtime_id, observed_at)
                SELECT ?, ?, ?, LEAST(CAST(? AS TIMESTAMP WITH TIME ZONE), CURRENT_TIMESTAMP)
                WHERE CAST(? AS TIMESTAMP WITH TIME ZONE) >= CURRENT_TIMESTAMP - (? * INTERVAL '1' SECOND)
                    AND CAST(? AS TIMESTAMP WITH TIME ZONE) <= CURRENT_TIMESTAMP + INTERVAL '5' SECOND
                ON CONFLICT (fleet_id, robot_id, runtime_id) DO UPDATE
                    SET observed_at = GREATEST(p.observed_at, EXCLUDED.observed_at)
                """, fleet, robot, runtime == null ? "" : runtime, timestamp, timestamp, ttl, timestamp) > 0;
    }

    public boolean online(String fleet, String robot, String runtime, long ttl) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM robot_presence p
                WHERE p.fleet_id = ? AND p.robot_id = ? AND p.runtime_id = ?
                    AND p.observed_at >= CURRENT_TIMESTAMP - (? * INTERVAL '1' SECOND)
                    AND
                """ + CURRENT_OWNER + " AND " + READY + ")", Boolean.class, fleet, robot, runtime == null ? "" : runtime, ttl));
    }

    public List<Expired> expired(String fleet, long ttl) {
        return jdbc.query("""
                SELECT p.robot_id, p.runtime_id FROM robot_presence p
                WHERE p.fleet_id = ? AND (p.observed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1' SECOND)
                OR NOT """ + READY + ") AND " + CURRENT_OWNER, (rs, n) -> new Expired(rs.getString(1),
                rs.getString(2).isEmpty() ? null : rs.getString(2)), fleet, ttl);
    }

    public void prune() {
        jdbc.update("DELETE FROM robot_presence WHERE observed_at < CURRENT_TIMESTAMP - INTERVAL '1' DAY");
    }
}
