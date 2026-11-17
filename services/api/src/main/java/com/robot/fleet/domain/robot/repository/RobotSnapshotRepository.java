package com.robot.fleet.domain.robot.repository;

import com.robot.fleet.domain.presence.repository.PresenceRepository;
import com.robot.fleet.domain.robot.dto.RobotStatusDto;
import com.robot.fleet.domain.telemetry.dto.BatteryDto;
import com.robot.fleet.domain.telemetry.dto.PoseDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class RobotSnapshotRepository {
    private final JdbcTemplate jdbc;
    private final String fleet;
    private final long ttl;

    public RobotSnapshotRepository(JdbcTemplate jdbc, @Value("${fleet.id:default}") String fleet,
                                   @Value("${fleet.offline-threshold-seconds:30}") long ttl) {
        this.jdbc = jdbc; this.fleet = fleet; this.ttl = ttl;
    }

    public List<RobotStatusDto> list(String after, Integer limit) { return query(after, limit, null); }
    public Optional<RobotStatusDto> find(String robotId) { return query("", 1, robotId).stream().findFirst(); }

    private List<RobotStatusDto> query(String after, Integer limit, String robotId) {
        String sql = """
                WITH decision AS (SELECT clock_timestamp() AS now),
                current_runtime AS (SELECT runtime_id FROM simulation_runtime_session WHERE fleet_id = ? ORDER BY epoch DESC LIMIT 1),
                robots AS (
                    SELECT s.robot_id, (SELECT runtime_id FROM current_runtime) AS runtime_id, TRUE AS managed
                    FROM simulation_robot s WHERE s.fleet_id = ? AND s.desired_state = 'RUNNING'
                    UNION
                    SELECT DISTINCT l.robot_id, CAST(NULL AS VARCHAR(36)), FALSE FROM robot_latest l
                    WHERE l.runtime_id = '' AND NOT EXISTS (SELECT 1 FROM simulation_robot s WHERE s.robot_id = l.robot_id)
                ), page AS (SELECT * FROM robots WHERE robot_id > ?
                """ + (robotId == null ? "" : " AND robot_id = ? ") + " ORDER BY robot_id LIMIT ?) " + """
                SELECT r.robot_id, r.runtime_id, pose.observed_at AS pose_time, pose.x, pose.y,
                    battery.observed_at AS battery_time, battery.level, motion.state,
                    COALESCE(p.robot_id IS NOT NULL AND p.observed_at >= d.now - (CAST(? AS BIGINT) * INTERVAL '1' SECOND)
                    AND
                """ + PresenceRepository.CURRENT_OWNER + " AND " + PresenceRepository.READY + ", FALSE) AS online " + """
                FROM page r CROSS JOIN decision d
                LEFT JOIN robot_latest pose ON pose.robot_id=r.robot_id AND pose.runtime_id=COALESCE(r.runtime_id,'')
                    AND pose.kind='pose' AND (NOT r.managed OR r.runtime_id IS NOT NULL)
                LEFT JOIN robot_latest battery ON battery.robot_id=r.robot_id AND battery.runtime_id=COALESCE(r.runtime_id,'')
                    AND battery.kind='battery' AND (NOT r.managed OR r.runtime_id IS NOT NULL)
                LEFT JOIN robot_latest motion ON motion.robot_id=r.robot_id AND motion.runtime_id=COALESCE(r.runtime_id,'')
                    AND motion.kind='status' AND (NOT r.managed OR r.runtime_id IS NOT NULL)
                LEFT JOIN robot_presence p ON p.fleet_id=? AND p.robot_id=r.robot_id AND p.runtime_id=COALESCE(r.runtime_id,'')
                ORDER BY r.robot_id
                """;
        var arguments = new ArrayList<Object>(List.of(fleet, fleet, after));
        if (robotId != null) arguments.add(robotId);
        arguments.add(limit == null ? Integer.MAX_VALUE : limit);
        arguments.add(ttl); arguments.add(fleet);
        return jdbc.query(sql, (rs, n) -> {
            var poseAt = rs.getObject("pose_time", OffsetDateTime.class);
            var batteryAt = rs.getObject("battery_time", OffsetDateTime.class);
            return new RobotStatusDto(rs.getString("robot_id"), rs.getBoolean("online"), poseAt,
                    poseAt == null ? null : PoseDto.of(rs.getDouble("x"), rs.getDouble("y"), poseAt),
                    batteryAt == null ? null : BatteryDto.of(rs.getFloat("level"), batteryAt),
                    rs.getString("state"), rs.getString("runtime_id"));
        }, arguments.toArray());
    }
}
