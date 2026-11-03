package com.robot.fleet.domain.presence.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class PresenceRepository {
    public record Expired(String robotId, String runtimeId, OffsetDateTime observedAt) { }
    private final JdbcTemplate jdbc;

    public static final String CURRENT_OWNER = """
            (NOT EXISTS (SELECT 1 FROM simulation_robot s WHERE s.robot_id = p.robot_id)
                AND p.runtime_id = ''
             OR EXISTS (SELECT 1 FROM simulation_robot s WHERE s.robot_id = p.robot_id
                AND s.fleet_id = p.fleet_id AND s.desired_state = 'RUNNING'
                AND p.runtime_id = (SELECT r.runtime_id FROM simulation_runtime_session r
                    WHERE r.fleet_id = p.fleet_id ORDER BY r.epoch DESC LIMIT 1)))
            """;

    public static final String READY = """
            (NOT EXISTS (SELECT 1 FROM simulation_robot s WHERE s.robot_id = p.robot_id)
             OR EXISTS (SELECT 1 FROM simulation_robot s WHERE s.robot_id = p.robot_id
                AND s.runtime_id = p.runtime_id AND s.runtime_state = 'RUNNING'
                AND s.reported_at >= d.now - INTERVAL '10' SECOND))
            """;

    public boolean observe(String fleet, String robot, String runtime, OffsetDateTime timestamp, long ttl) {
        Boolean ready = jdbc.queryForObject("""
                WITH decision AS MATERIALIZED (SELECT clock_timestamp() AS now),
                input(fleet_id, robot_id, runtime_id, observed_at, source_timestamp) AS (
                    SELECT CAST(? AS VARCHAR), CAST(? AS VARCHAR), CAST(? AS VARCHAR),
                        LEAST(CAST(? AS TIMESTAMP WITH TIME ZONE), d.now), CAST(? AS TIMESTAMP WITH TIME ZONE)
                    FROM decision d WHERE CAST(? AS TIMESTAMP WITH TIME ZONE) >= d.now - (? * INTERVAL '1' SECOND)
                        AND CAST(? AS TIMESTAMP WITH TIME ZONE) <= d.now + INTERVAL '5' SECOND
                ), eligible AS (SELECT p.* FROM input p WHERE
                """ + CURRENT_OWNER + """
                ), saved AS (
                    INSERT INTO robot_presence AS p (fleet_id, robot_id, runtime_id, observed_at, source_timestamp)
                    SELECT * FROM eligible
                    ON CONFLICT (fleet_id, robot_id, runtime_id) DO UPDATE
                        SET observed_at = GREATEST(p.observed_at, EXCLUDED.observed_at),
                            source_timestamp = EXCLUDED.source_timestamp
                        WHERE EXCLUDED.source_timestamp > p.source_timestamp
                    RETURNING p.fleet_id,p.robot_id,p.runtime_id,p.observed_at,p.source_timestamp
                ) SELECT CASE WHEN EXISTS(SELECT 1 FROM saved) THEN
                    EXISTS(SELECT 1 FROM saved p CROSS JOIN decision d
                        WHERE p.observed_at >= d.now - (? * INTERVAL '1' SECOND) AND
                """ + READY + ") WHEN EXISTS(SELECT 1 FROM eligible) THEN NULL ELSE FALSE END",
                Boolean.class, fleet, robot, runtime == null ? "" : runtime, timestamp, timestamp, timestamp, ttl, timestamp, ttl);
        return ready == null ? online(fleet, robot, runtime, ttl) : ready;
    }

    public boolean online(String fleet, String robot, String runtime, long ttl) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH decision AS MATERIALIZED (SELECT clock_timestamp() AS now)
                SELECT EXISTS (SELECT 1 FROM robot_presence p CROSS JOIN decision d
                WHERE p.fleet_id = ? AND p.robot_id = ? AND p.runtime_id = ?
                    AND p.observed_at >= d.now - (? * INTERVAL '1' SECOND)
                    AND
                """ + CURRENT_OWNER + " AND " + READY + ")", Boolean.class, fleet, robot, runtime == null ? "" : runtime, ttl));
    }

    public List<Expired> expired(String fleet, long ttl) {
        return jdbc.query("""
                WITH decision AS MATERIALIZED (SELECT clock_timestamp() AS now)
                SELECT p.robot_id, p.runtime_id, p.source_timestamp FROM robot_presence p CROSS JOIN decision d
                WHERE p.fleet_id = ? AND (p.observed_at < d.now - (? * INTERVAL '1' SECOND)
                OR NOT """ + READY + ") AND " + CURRENT_OWNER, (rs, n) -> new Expired(rs.getString(1),
                rs.getString(2).isEmpty() ? null : rs.getString(2), rs.getObject(3, OffsetDateTime.class)), fleet, ttl);
    }

    public void prune() {
        jdbc.update("DELETE FROM robot_presence WHERE observed_at < clock_timestamp() - INTERVAL '1' DAY");
    }
}
