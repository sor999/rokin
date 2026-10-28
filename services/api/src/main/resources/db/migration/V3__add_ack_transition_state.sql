CREATE TABLE robot_ack_state (
    cmd_id     UUID PRIMARY KEY,
    timestamp  TIMESTAMPTZ NOT NULL,
    robot_id   VARCHAR(64) NOT NULL,
    status     VARCHAR(16) NOT NULL CHECK (status IN ('accepted', 'done', 'failed'))
);

INSERT INTO robot_ack_state (cmd_id, timestamp, robot_id, status)
SELECT DISTINCT ON (cmd_id) cmd_id, timestamp, robot_id, status
FROM robot_ack
WHERE status IN ('accepted', 'done', 'failed')
ORDER BY cmd_id,
         CASE WHEN status IN ('done', 'failed') THEN 0 ELSE 1 END,
         timestamp ASC, id ASC;
