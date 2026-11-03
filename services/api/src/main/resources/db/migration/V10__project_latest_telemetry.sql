CREATE TABLE robot_latest (
    robot_id VARCHAR(128) NOT NULL,
    runtime_id VARCHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('pose','battery','status')),
    event_id UUID NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    x DOUBLE PRECISION,
    y DOUBLE PRECISION,
    level REAL,
    state VARCHAR(256),
    PRIMARY KEY (robot_id, runtime_id, kind)
);

CREATE FUNCTION project_robot_latest() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    px DOUBLE PRECISION;
    py DOUBLE PRECISION;
    battery REAL;
    motion VARCHAR(256);
BEGIN
    IF TG_ARGV[0] = 'pose' THEN px := NEW.x; py := NEW.y;
    ELSIF TG_ARGV[0] = 'battery' THEN battery := NEW.level;
    ELSE motion := NEW.state;
    END IF;
    INSERT INTO robot_latest AS latest (robot_id,runtime_id,kind,event_id,observed_at,x,y,level,state)
    VALUES (NEW.robot_id,COALESCE(NEW.simulation_runtime_id,''),TG_ARGV[0],NEW.event_id,NEW.timestamp,px,py,battery,motion)
    ON CONFLICT (robot_id,runtime_id,kind) DO UPDATE SET
        event_id=EXCLUDED.event_id,observed_at=EXCLUDED.observed_at,
        x=EXCLUDED.x,y=EXCLUDED.y,level=EXCLUDED.level,state=EXCLUDED.state
    WHERE (EXCLUDED.observed_at,EXCLUDED.event_id) > (latest.observed_at,latest.event_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER project_pose AFTER INSERT ON telemetry_pose
    FOR EACH ROW EXECUTE FUNCTION project_robot_latest('pose');
CREATE TRIGGER project_battery AFTER INSERT ON telemetry_battery
    FOR EACH ROW EXECUTE FUNCTION project_robot_latest('battery');
CREATE TRIGGER project_status AFTER INSERT ON telemetry_status
    FOR EACH ROW EXECUTE FUNCTION project_robot_latest('status');

INSERT INTO robot_latest(robot_id,runtime_id,kind,event_id,observed_at,x,y)
SELECT DISTINCT ON (robot_id, COALESCE(simulation_runtime_id,''))
    robot_id,COALESCE(simulation_runtime_id,''),'pose',event_id,timestamp,x,y
FROM telemetry_pose ORDER BY robot_id,COALESCE(simulation_runtime_id,''),timestamp DESC,event_id DESC;
INSERT INTO robot_latest(robot_id,runtime_id,kind,event_id,observed_at,level)
SELECT DISTINCT ON (robot_id, COALESCE(simulation_runtime_id,''))
    robot_id,COALESCE(simulation_runtime_id,''),'battery',event_id,timestamp,level
FROM telemetry_battery ORDER BY robot_id,COALESCE(simulation_runtime_id,''),timestamp DESC,event_id DESC;
INSERT INTO robot_latest(robot_id,runtime_id,kind,event_id,observed_at,state)
SELECT DISTINCT ON (robot_id, COALESCE(simulation_runtime_id,''))
    robot_id,COALESCE(simulation_runtime_id,''),'status',event_id,timestamp,state
FROM telemetry_status ORDER BY robot_id,COALESCE(simulation_runtime_id,''),timestamp DESC,event_id DESC;
