package repository

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Migrate는 Worker가 필요로 하는 테이블을 자동 생성한다.
// 스키마 주도권은 api-spring(Flyway)이 가지나, 개발 환경에서 Worker 단독 실행을 지원하기 위해 포함한다.
func Migrate(ctx context.Context, pool *pgxpool.Pool) error {
	statements := []string{
		`CREATE TABLE IF NOT EXISTS telemetry_pose (
			id         BIGSERIAL    PRIMARY KEY,
			event_id   UUID         NOT NULL UNIQUE,
			timestamp  TIMESTAMPTZ  NOT NULL,
			robot_id   VARCHAR(64)  NOT NULL,
			x          DOUBLE PRECISION NOT NULL,
			y          DOUBLE PRECISION NOT NULL,
			created_at TIMESTAMPTZ  DEFAULT NOW(),
			updated_at TIMESTAMPTZ  DEFAULT NOW()
		)`,
		`CREATE INDEX IF NOT EXISTS idx_pose_robot_id ON telemetry_pose (robot_id)`,
		`CREATE INDEX IF NOT EXISTS idx_pose_timestamp ON telemetry_pose (timestamp DESC)`,

		`CREATE TABLE IF NOT EXISTS telemetry_battery (
			id         BIGSERIAL    PRIMARY KEY,
			event_id   UUID         NOT NULL UNIQUE,
			timestamp  TIMESTAMPTZ  NOT NULL,
			robot_id   VARCHAR(64)  NOT NULL,
			level      REAL         NOT NULL,
			created_at TIMESTAMPTZ  DEFAULT NOW(),
			updated_at TIMESTAMPTZ  DEFAULT NOW()
		)`,
		`CREATE INDEX IF NOT EXISTS idx_battery_robot_id ON telemetry_battery (robot_id)`,

		`CREATE TABLE IF NOT EXISTS telemetry_status (
			id         BIGSERIAL    PRIMARY KEY,
			event_id   UUID         NOT NULL UNIQUE,
			timestamp  TIMESTAMPTZ  NOT NULL,
			robot_id   VARCHAR(64)  NOT NULL,
			state      VARCHAR(32)  NOT NULL,
			created_at TIMESTAMPTZ  DEFAULT NOW(),
			updated_at TIMESTAMPTZ  DEFAULT NOW()
		)`,
		`CREATE INDEX IF NOT EXISTS idx_status_robot_id ON telemetry_status (robot_id)`,

		`CREATE TABLE IF NOT EXISTS robot_ack (
				id         BIGSERIAL    PRIMARY KEY,
				cmd_id     UUID         NOT NULL,
				timestamp  TIMESTAMPTZ  NOT NULL,
				robot_id   VARCHAR(64)  NOT NULL,
				status     VARCHAR(16)  NOT NULL,
				message    TEXT,
				created_at TIMESTAMPTZ  DEFAULT NOW(),
				updated_at TIMESTAMPTZ  DEFAULT NOW()
			)`,
		`CREATE INDEX IF NOT EXISTS idx_ack_cmd_id ON robot_ack (cmd_id)`,
		`CREATE UNIQUE INDEX IF NOT EXISTS uq_ack_dedup ON robot_ack (cmd_id, status, timestamp)`,
		`ALTER TABLE telemetry_pose ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW()`,
		`UPDATE telemetry_pose SET updated_at = created_at WHERE updated_at IS NULL`,
		`ALTER TABLE telemetry_pose ALTER COLUMN updated_at SET DEFAULT NOW()`,
		`ALTER TABLE telemetry_pose ALTER COLUMN updated_at SET NOT NULL`,
		`ALTER TABLE telemetry_battery ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW()`,
		`UPDATE telemetry_battery SET updated_at = created_at WHERE updated_at IS NULL`,
		`ALTER TABLE telemetry_battery ALTER COLUMN updated_at SET DEFAULT NOW()`,
		`ALTER TABLE telemetry_battery ALTER COLUMN updated_at SET NOT NULL`,
		`ALTER TABLE telemetry_status ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW()`,
		`UPDATE telemetry_status SET updated_at = created_at WHERE updated_at IS NULL`,
		`ALTER TABLE telemetry_status ALTER COLUMN updated_at SET DEFAULT NOW()`,
		`ALTER TABLE telemetry_status ALTER COLUMN updated_at SET NOT NULL`,
		`ALTER TABLE robot_ack ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW()`,
		`UPDATE robot_ack SET updated_at = created_at WHERE updated_at IS NULL`,
		`ALTER TABLE robot_ack ALTER COLUMN updated_at SET DEFAULT NOW()`,
		`ALTER TABLE robot_ack ALTER COLUMN updated_at SET NOT NULL`,
	}

	for _, stmt := range statements {
		if _, err := pool.Exec(ctx, stmt); err != nil {
			return fmt.Errorf("migrate 실패: %w", err)
		}
	}
	return nil
}
