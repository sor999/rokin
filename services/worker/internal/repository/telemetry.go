package repository

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Row 타입 정의

type PoseRow struct {
	RuntimeID string
	EventID   string
	Timestamp time.Time
	RobotID   string
	X         float64
	Y         float64
}

type BatteryRow struct {
	RuntimeID string
	EventID   string
	Timestamp time.Time
	RobotID   string
	Level     float64
}

type StatusRow struct {
	RuntimeID string
	EventID   string
	Timestamp time.Time
	RobotID   string
	State     string
}

type AckRow struct {
	CmdID     string
	Timestamp time.Time
	RobotID   string
	Status    string
	Message   string
}

// Repository는 DB 접근 계층이다.
type Repository struct {
	pool *pgxpool.Pool
}

func New(pool *pgxpool.Pool) *Repository {
	return &Repository{pool: pool}
}

// BatchInsertPose는 pose 행을 Batch로 삽입한다. event_id 중복 시 무시(멱등성 보장).
func (r *Repository) BatchInsertPose(ctx context.Context, rows []PoseRow) error {
	if len(rows) == 0 {
		return nil
	}
	batch := &pgx.Batch{}
	for _, row := range rows {
		batch.Queue(
			`INSERT INTO telemetry_pose (event_id, timestamp, robot_id, x, y, simulation_runtime_id)
			 VALUES ($1, $2, $3, $4, $5, NULLIF($6, ''))
			 ON CONFLICT (event_id) DO NOTHING`,
			row.EventID, row.Timestamp, row.RobotID, row.X, row.Y, row.RuntimeID,
		)
	}
	return r.sendBatch(ctx, batch, len(rows))
}

// BatchInsertBattery는 battery 행을 Batch로 삽입한다.
func (r *Repository) BatchInsertBattery(ctx context.Context, rows []BatteryRow) error {
	if len(rows) == 0 {
		return nil
	}
	batch := &pgx.Batch{}
	for _, row := range rows {
		batch.Queue(
			`INSERT INTO telemetry_battery (event_id, timestamp, robot_id, level, simulation_runtime_id)
			 VALUES ($1, $2, $3, $4, NULLIF($5, ''))
			 ON CONFLICT (event_id) DO NOTHING`,
			row.EventID, row.Timestamp, row.RobotID, row.Level, row.RuntimeID,
		)
	}
	return r.sendBatch(ctx, batch, len(rows))
}

// BatchInsertStatus는 status 행을 Batch로 삽입한다.
func (r *Repository) BatchInsertStatus(ctx context.Context, rows []StatusRow) error {
	if len(rows) == 0 {
		return nil
	}
	batch := &pgx.Batch{}
	for _, row := range rows {
		batch.Queue(
			`INSERT INTO telemetry_status (event_id, timestamp, robot_id, state, simulation_runtime_id)
			 VALUES ($1, $2, $3, $4, NULLIF($5, ''))
			 ON CONFLICT (event_id) DO NOTHING`,
			row.EventID, row.Timestamp, row.RobotID, row.State, row.RuntimeID,
		)
	}
	return r.sendBatch(ctx, batch, len(rows))
}

// BatchInsertAck는 ack 행을 Batch로 삽입한다.
func (r *Repository) BatchInsertAck(ctx context.Context, rows []AckRow) error {
	if len(rows) == 0 {
		return nil
	}
	batch := &pgx.Batch{}
	for _, row := range rows {
		batch.Queue(
			`WITH transition AS (
                INSERT INTO robot_ack_state (cmd_id, timestamp, robot_id, status)
                VALUES ($1, $2, $3, $4)
                ON CONFLICT (cmd_id) DO UPDATE
                SET timestamp = EXCLUDED.timestamp, status = EXCLUDED.status
                WHERE robot_ack_state.robot_id = EXCLUDED.robot_id
                  AND robot_ack_state.status = 'accepted'
                  AND EXCLUDED.status IN ('done', 'failed')
                RETURNING cmd_id
            )
            INSERT INTO robot_ack (cmd_id, timestamp, robot_id, status, message)
            SELECT cmd_id, $2, $3, $4, $5 FROM transition`,
			row.CmdID, row.Timestamp, row.RobotID, row.Status, row.Message,
		)
	}
	return r.sendBatch(ctx, batch, len(rows))
}

func (r *Repository) sendBatch(ctx context.Context, batch *pgx.Batch, count int) error {
	br := r.pool.SendBatch(ctx, batch)
	defer br.Close()

	for i := 0; i < count; i++ {
		if _, err := br.Exec(); err != nil {
			return fmt.Errorf("batch exec [%d]: %w", i, err)
		}
	}
	if err := br.Close(); err != nil {
		return fmt.Errorf("batch close: %w", err)
	}
	return nil
}
