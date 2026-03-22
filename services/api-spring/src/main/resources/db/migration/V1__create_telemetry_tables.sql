-- Flyway V1: 텔레메트리 적재 테이블 생성
-- 스키마 주도권은 api-spring(Flyway)이 보유. worker-go는 이 스키마를 따른다.

CREATE TABLE IF NOT EXISTS telemetry_pose (
    id         BIGSERIAL    PRIMARY KEY,
    event_id   UUID         NOT NULL UNIQUE,
    timestamp  TIMESTAMPTZ  NOT NULL,
    robot_id   VARCHAR(64)  NOT NULL,
    x          DOUBLE PRECISION NOT NULL,
    y          DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ  DEFAULT NOW(),
    updated_at TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pose_robot_id  ON telemetry_pose (robot_id);
CREATE INDEX IF NOT EXISTS idx_pose_timestamp ON telemetry_pose (timestamp DESC);

CREATE TABLE IF NOT EXISTS telemetry_battery (
    id         BIGSERIAL    PRIMARY KEY,
    event_id   UUID         NOT NULL UNIQUE,
    timestamp  TIMESTAMPTZ  NOT NULL,
    robot_id   VARCHAR(64)  NOT NULL,
    level      REAL         NOT NULL,
    created_at TIMESTAMPTZ  DEFAULT NOW(),
    updated_at TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_battery_robot_id ON telemetry_battery (robot_id);

CREATE TABLE IF NOT EXISTS telemetry_status (
    id         BIGSERIAL    PRIMARY KEY,
    event_id   UUID         NOT NULL UNIQUE,
    timestamp  TIMESTAMPTZ  NOT NULL,
    robot_id   VARCHAR(64)  NOT NULL,
    state      VARCHAR(32)  NOT NULL,
    created_at TIMESTAMPTZ  DEFAULT NOW(),
    updated_at TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_status_robot_id ON telemetry_status (robot_id);

CREATE TABLE IF NOT EXISTS robot_ack (
    id         BIGSERIAL    PRIMARY KEY,
    cmd_id     UUID         NOT NULL,
    timestamp  TIMESTAMPTZ  NOT NULL,
    robot_id   VARCHAR(64)  NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    message    TEXT,
    created_at TIMESTAMPTZ  DEFAULT NOW(),
    updated_at TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ack_cmd_id   ON robot_ack (cmd_id);
-- 복합 유니크 제약(cmd_id, status, timestamp) 삭제: 순수 로그성 삽입 성능 향상
