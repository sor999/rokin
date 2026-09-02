ALTER TABLE telemetry_pose
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();

UPDATE telemetry_pose
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE telemetry_pose
    ALTER COLUMN updated_at SET DEFAULT NOW();

ALTER TABLE telemetry_pose
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE telemetry_battery
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();

UPDATE telemetry_battery
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE telemetry_battery
    ALTER COLUMN updated_at SET DEFAULT NOW();

ALTER TABLE telemetry_battery
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE telemetry_status
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();

UPDATE telemetry_status
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE telemetry_status
    ALTER COLUMN updated_at SET DEFAULT NOW();

ALTER TABLE telemetry_status
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE robot_ack
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();

UPDATE robot_ack
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE robot_ack
    ALTER COLUMN updated_at SET DEFAULT NOW();

ALTER TABLE robot_ack
    ALTER COLUMN updated_at SET NOT NULL;
