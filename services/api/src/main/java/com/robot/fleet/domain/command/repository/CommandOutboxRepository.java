package com.robot.fleet.domain.command.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class CommandOutboxRepository {
    private final JdbcTemplate jdbc;
    public CommandOutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Entry(UUID cmdId, String robotId, String requestHash, String payload,
                        OffsetDateTime expiresAt, OffsetDateTime receivedAt, int attempts, boolean unknown) { }
    private List<Entry> find(String where, Object... args) {
        return jdbc.query("SELECT * FROM command_outbox WHERE " + where, (rs, n) -> new Entry(
            rs.getObject("cmd_id", UUID.class), rs.getString("robot_id"), rs.getString("request_hash"),
            rs.getString("payload"), rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("received_at", OffsetDateTime.class), rs.getInt("attempts"), rs.getBoolean("outcome_unknown")), args);
    }
    public Optional<Entry> byId(String fleet, UUID id) { return find("fleet_id=? AND cmd_id=?", fleet, id).stream().findFirst(); }
    public Optional<Entry> byKey(String fleet, UUID key) { return find("fleet_id=? AND idempotency_key=?", fleet, key).stream().findFirst(); }
    public OffsetDateTime now() { return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class); }
    public long nextSequence(String fleet, String robot) {
        jdbc.update("INSERT INTO command_sequence(fleet_id,robot_id) VALUES (?,?) ON CONFLICT DO NOTHING", fleet, robot);
        return jdbc.queryForObject("UPDATE command_sequence SET sequence=sequence+1 WHERE fleet_id=? AND robot_id=? RETURNING sequence", Long.class, fleet, robot);
    }
    public void save(UUID id, String fleet, String robot, UUID key, String hash, String payload, long seq, OffsetDateTime expiry) {
        jdbc.update("INSERT INTO command_outbox(cmd_id,fleet_id,robot_id,idempotency_key,request_hash,payload,sequence,expires_at) VALUES (?,?,?,?,?,?,?,?)",
            id, fleet, robot, key, hash, payload, seq, expiry);
    }
    public List<Entry> claim(String fleet, UUID token) { return claim(fleet,token,10); }
    public List<Entry> claim(String fleet, UUID token, int limit) {
        if (limit < 1 || limit > 10) throw new IllegalArgumentException("Invalid publisher capacity");
        return jdbc.query("""
            WITH candidates AS (
                SELECT cmd_id FROM command_outbox WHERE fleet_id=? AND received_at IS NULL
                  AND expires_at>clock_timestamp() AND next_attempt_at<=clock_timestamp()
                  AND (lease_until IS NULL OR lease_until<clock_timestamp())
                ORDER BY expires_at,created_at LIMIT ? FOR UPDATE SKIP LOCKED
            ) UPDATE command_outbox o SET lease_token=?,lease_until=clock_timestamp()+interval '1 second',
                attempts=attempts+1 FROM candidates c WHERE o.cmd_id=c.cmd_id RETURNING o.*
            """, (rs,n) -> new Entry(rs.getObject("cmd_id", UUID.class), rs.getString("robot_id"),
                rs.getString("request_hash"), rs.getString("payload"), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class),rs.getInt("attempts"),rs.getBoolean("outcome_unknown")), fleet, limit, token);
    }
    public void release(UUID cmd, UUID token) {
        jdbc.update("UPDATE command_outbox SET lease_until=NULL,lease_token=NULL,next_attempt_at=clock_timestamp()+interval '2 seconds' WHERE cmd_id=? AND lease_token=?", cmd, token);
    }
    public void received(String fleet, UUID cmd, String robot, boolean unknown) {
        jdbc.update("UPDATE command_outbox SET received_at=COALESCE(received_at,clock_timestamp()),outcome_unknown=outcome_unknown OR ? WHERE fleet_id=? AND cmd_id=? AND robot_id=?", unknown, fleet, cmd, robot);
    }
    public boolean receipt(String fleet, UUID event) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM command_ack_receipt a JOIN command_outbox c ON c.cmd_id=a.cmd_id WHERE c.fleet_id=? AND a.event_id=?)", Boolean.class, fleet, event));
    }
    public double count(String fleet, String state) {
        String condition=switch(state) {
            case "pending" -> "received_at IS NULL AND expires_at>clock_timestamp()";
            case "expired" -> "received_at IS NULL AND expires_at<=clock_timestamp() AND attempts=0";
            default -> "outcome_unknown OR (received_at IS NULL AND expires_at<=clock_timestamp() AND attempts>0)";
        };
        return jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE fleet_id=? AND ("+condition+") AND NOT EXISTS(SELECT 1 FROM robot_ack_state a WHERE a.cmd_id=command_outbox.cmd_id AND a.status IN ('done','failed'))", Double.class, fleet);
    }
    public double oldest(String fleet) {
        return jdbc.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp()-MIN(created_at)),0) FROM command_outbox WHERE fleet_id=? AND received_at IS NULL",Double.class,fleet);
    }
}
