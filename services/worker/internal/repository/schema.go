package repository

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
)

func CheckSchema(ctx context.Context, pool *pgxpool.Pool) error {
	queries := []string{
		`SELECT event_id, timestamp, robot_id, simulation_runtime_id, x, y, created_at, updated_at FROM telemetry_pose LIMIT 0`,
		`SELECT event_id, timestamp, robot_id, simulation_runtime_id, level, created_at, updated_at FROM telemetry_battery LIMIT 0`,
		`SELECT event_id, timestamp, robot_id, simulation_runtime_id, state, created_at, updated_at FROM telemetry_status LIMIT 0`,
		`SELECT cmd_id, timestamp, robot_id, status, message, created_at, updated_at FROM robot_ack LIMIT 0`,
		`SELECT cmd_id, timestamp, robot_id, status FROM robot_ack_state LIMIT 0`,
	}
	for _, query := range queries {
		rows, err := pool.Query(ctx, query)
		if err != nil {
			return fmt.Errorf("API의 Flyway 마이그레이션을 먼저 적용하세요: %w", err)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return fmt.Errorf("스키마 조회 실패: %w", err)
		}
	}
	return nil
}
