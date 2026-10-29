package repository

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

func testDatabase(t *testing.T, version int) *pgxpool.Pool {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL is required for isolated PostgreSQL tests")
	}
	ctx := context.Background()
	admin, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	schema := fmt.Sprintf("worker_test_%d", time.Now().UnixNano())
	identifier := pgx.Identifier{schema}.Sanitize()
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+identifier); err != nil {
		admin.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, err := admin.Exec(context.Background(), "DROP SCHEMA "+identifier+" CASCADE")
		admin.Close()
		if err != nil {
			t.Error(err)
		}
	})
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	migrations := []string{"V1__create_telemetry_tables.sql", "V2__add_updated_at_columns.sql", "V3__add_ack_transition_state.sql", "V4__add_simulation_registry.sql", "V5__fence_simulation_runtimes.sql"}
	for _, migration := range migrations[:version] {
		contents, err := os.ReadFile(filepath.Join("..", "..", "..", "api", "src", "main", "resources", "db", "migration", migration))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := pool.Exec(ctx, string(contents)); err != nil {
			t.Fatal(err)
		}
	}
	return pool
}

func ack(cmd, status string, seconds int) AckRow {
	return AckRow{CmdID: cmd, RobotID: "robot-1", Status: status, Timestamp: time.Unix(int64(seconds), 0), Message: status}
}

func TestAckTransitions(t *testing.T) {
	for _, terminal := range []string{"done", "failed"} {
		t.Run(terminal, func(t *testing.T) {
			pool := testDatabase(t, 5)
			ctx := context.Background()
			if err := CheckSchema(ctx, pool); err != nil {
				t.Fatal(err)
			}
			repo := New(pool)
			cmd := "00000000-0000-0000-0000-000000000001"
			rows := []AckRow{ack(cmd, "accepted", 1), ack(cmd, "accepted", 1), ack(cmd, "accepted", 2), ack(cmd, terminal, 3), ack(cmd, terminal, 4), ack(cmd, "accepted", 5), ack(cmd, "done", 6), ack(cmd, "failed", 7)}
			if err := repo.BatchInsertAck(ctx, rows); err != nil {
				t.Fatal(err)
			}
			if err := repo.BatchInsertAck(ctx, rows); err != nil {
				t.Fatal(err)
			}
			var count int
			if err := pool.QueryRow(ctx, "SELECT count(*) FROM robot_ack").Scan(&count); err != nil {
				t.Fatal(err)
			}
			if count != 2 {
				t.Fatalf("history count = %d, want 2", count)
			}
			var status string
			var timestamp time.Time
			if err := pool.QueryRow(ctx, "SELECT status, timestamp FROM robot_ack_state WHERE cmd_id=$1", cmd).Scan(&status, &timestamp); err != nil {
				t.Fatal(err)
			}
			if status != terminal || !timestamp.Equal(time.Unix(3, 0)) {
				t.Fatalf("state = %s at %s", status, timestamp)
			}
		})
	}
}

func TestAckTerminalArrivesFirst(t *testing.T) {
	pool := testDatabase(t, 3)
	ctx := context.Background()
	cmd := "00000000-0000-0000-0000-000000000002"
	rows := []AckRow{ack(cmd, "done", 3), ack(cmd, "accepted", 1), ack(cmd, "failed", 4)}
	if err := New(pool).BatchInsertAck(ctx, rows); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM robot_ack WHERE status='done'").Scan(&count); err != nil {
		t.Fatal(err)
	}
	var total int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM robot_ack").Scan(&total); err != nil {
		t.Fatal(err)
	}
	if count != 1 || total != 1 {
		t.Fatalf("done=%d total=%d", count, total)
	}
}

func TestConcurrentTerminalAck(t *testing.T) {
	pool := testDatabase(t, 3)
	ctx := context.Background()
	cmd := "00000000-0000-0000-0000-000000000003"
	repo := New(pool)
	if err := repo.BatchInsertAck(ctx, []AckRow{ack(cmd, "accepted", 1)}); err != nil {
		t.Fatal(err)
	}
	start := make(chan struct{})
	errors := make(chan error, 16)
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			status := "done"
			if i%2 == 0 {
				status = "failed"
			}
			errors <- repo.BatchInsertAck(ctx, []AckRow{ack(cmd, status, i+2)})
		}(i)
	}
	close(start)
	wg.Wait()
	close(errors)
	for err := range errors {
		if err != nil {
			t.Fatal(err)
		}
	}
	var count int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM robot_ack a JOIN robot_ack_state s USING (cmd_id) WHERE a.status=s.status AND a.timestamp=s.timestamp AND a.status IN ('done','failed')`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("matching terminal history count = %d", count)
	}
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM robot_ack").Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 2 {
		t.Fatalf("history count = %d, want 2", count)
	}
}

func TestInvalidAckRollsBackStateAndHistory(t *testing.T) {
	pool := testDatabase(t, 3)
	ctx := context.Background()
	cmd := "00000000-0000-0000-0000-000000000004"
	if err := New(pool).BatchInsertAck(ctx, []AckRow{ack(cmd, "accepted", 1), ack(cmd, "invalid", 2)}); err == nil {
		t.Fatal("expected status constraint error")
	}
	for _, table := range []string{"robot_ack", "robot_ack_state"} {
		var count int
		if err := pool.QueryRow(ctx, "SELECT count(*) FROM "+table).Scan(&count); err != nil {
			t.Fatal(err)
		}
		if count != 0 {
			t.Fatalf("%s retained %d rows after rollback", table, count)
		}
	}
}

func TestCheckSchemaRequiresFlyway(t *testing.T) {
	pool := testDatabase(t, 2)
	ctx := context.Background()
	if err := CheckSchema(ctx, pool); err == nil {
		t.Fatal("expected missing V3 schema error")
	}
	var count int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name='robot_ack_state'").Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatal("schema check created a table")
	}
}

func TestLegacyHistoryBackfillSuppressesReplays(t *testing.T) {
	for _, workerIndex := range []bool{false, true} {
		t.Run(fmt.Sprintf("worker_index_%t", workerIndex), func(t *testing.T) {
			pool := testDatabase(t, 2)
			ctx := context.Background()
			cmd := "00000000-0000-0000-0000-000000000005"
			for _, row := range []AckRow{ack(cmd, "accepted", 1), ack(cmd, "accepted", 2), ack(cmd, "done", 3)} {
				if _, err := pool.Exec(ctx, `INSERT INTO robot_ack (cmd_id,timestamp,robot_id,status,message) VALUES ($1,$2,$3,$4,$5)`, row.CmdID, row.Timestamp, row.RobotID, row.Status, row.Message); err != nil {
					t.Fatal(err)
				}
			}
			if workerIndex {
				if _, err := pool.Exec(ctx, "CREATE UNIQUE INDEX uq_ack_dedup ON robot_ack(cmd_id,status,timestamp)"); err != nil {
					t.Fatal(err)
				}
			} else {
				if _, err := pool.Exec(ctx, "INSERT INTO robot_ack(cmd_id,timestamp,robot_id,status) SELECT cmd_id,timestamp,robot_id,status FROM robot_ack WHERE status='done'"); err != nil {
					t.Fatal(err)
				}
			}
			var before int
			if err := pool.QueryRow(ctx, "SELECT count(*) FROM robot_ack").Scan(&before); err != nil {
				t.Fatal(err)
			}
			sql, err := os.ReadFile(filepath.Join("..", "..", "..", "api", "src", "main", "resources", "db", "migration", "V3__add_ack_transition_state.sql"))
			if err != nil {
				t.Fatal(err)
			}
			if _, err := pool.Exec(ctx, string(sql)); err != nil {
				t.Fatal(err)
			}
			if err := New(pool).BatchInsertAck(ctx, []AckRow{ack(cmd, "accepted", 4), ack(cmd, "done", 5), ack(cmd, "failed", 6)}); err != nil {
				t.Fatal(err)
			}
			var after int
			if err := pool.QueryRow(ctx, "SELECT count(*) FROM robot_ack").Scan(&after); err != nil {
				t.Fatal(err)
			}
			if after != before {
				t.Fatalf("history count changed: %d -> %d", before, after)
			}
			var state string
			if err := pool.QueryRow(ctx, "SELECT status FROM robot_ack_state WHERE cmd_id=$1", cmd).Scan(&state); err != nil {
				t.Fatal(err)
			}
			if state != "done" {
				t.Fatalf("state = %s", state)
			}
		})
	}
}

func TestTelemetryPreservesRuntimeAndIdempotency(t *testing.T) {
	pool := testDatabase(t, 5)
	ctx := context.Background()
	repo := New(pool)
	now := time.Now().UTC()
	runtimeID := "00000000-0000-0000-0000-000000000099"
	poseID := "00000000-0000-0000-0000-000000000011"
	if err := repo.BatchInsertPose(ctx, []PoseRow{{EventID: poseID, Timestamp: now, RobotID: "runtime_robot", RuntimeID: runtimeID, X: 1, Y: 2}}); err != nil {
		t.Fatal(err)
	}
	if err := repo.BatchInsertBattery(ctx, []BatteryRow{{EventID: "00000000-0000-0000-0000-000000000012", Timestamp: now, RobotID: "runtime_robot", RuntimeID: runtimeID, Level: 100}}); err != nil {
		t.Fatal(err)
	}
	if err := repo.BatchInsertStatus(ctx, []StatusRow{{EventID: "00000000-0000-0000-0000-000000000013", Timestamp: now, RobotID: "runtime_robot", RuntimeID: runtimeID, State: "idle"}}); err != nil {
		t.Fatal(err)
	}
	if err := repo.BatchInsertPose(ctx, []PoseRow{{EventID: poseID, Timestamp: now, RobotID: "runtime_robot", RuntimeID: "old", X: 9}}); err != nil {
		t.Fatal(err)
	}
	for _, table := range []string{"telemetry_pose", "telemetry_battery", "telemetry_status"} {
		var actual string
		if err := pool.QueryRow(ctx, "SELECT simulation_runtime_id FROM "+table+" WHERE robot_id='runtime_robot'").Scan(&actual); err != nil {
			t.Fatal(err)
		}
		if actual != runtimeID {
			t.Fatalf("%s runtime=%q", table, actual)
		}
	}
	if err := repo.BatchInsertPose(ctx, []PoseRow{{EventID: "00000000-0000-0000-0000-000000000014", Timestamp: now, RobotID: "static_robot"}}); err != nil {
		t.Fatal(err)
	}
	var empty bool
	if err := pool.QueryRow(ctx, "SELECT simulation_runtime_id IS NULL FROM telemetry_pose WHERE robot_id='static_robot'").Scan(&empty); err != nil || !empty {
		t.Fatalf("static compatibility: null=%t err=%v", empty, err)
	}
}

func TestCheckSchemaRequiresRuntimeMigration(t *testing.T) {
	pool := testDatabase(t, 4)
	if err := CheckSchema(context.Background(), pool); err == nil {
		t.Fatal("expected missing V5 runtime columns")
	}
}
