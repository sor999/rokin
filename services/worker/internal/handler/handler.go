package handler

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	kafkapkg "github.com/sor999/robot/services/worker/internal/kafka"
	"github.com/sor999/robot/services/worker/internal/repository"
)

type telemetryEnvelope struct {
	RuntimeID string          `json:"simulation_runtime_id"`
	EventID   string          `json:"event_id"`
	Timestamp string          `json:"timestamp"`
	RobotID   string          `json:"robot_id"`
	Type      string          `json:"type"`
	Data      json.RawMessage `json:"data"`
}

type ackEnvelope struct {
	EventID   string `json:"event_id"`
	FleetID   string `json:"fleet_id"`
	CmdID     string `json:"cmd_id"`
	Timestamp string `json:"timestamp"`
	RobotID   string `json:"robot_id"`
	Status    string `json:"status"`
	Message   string `json:"message"`
}

type Store interface {
	BatchInsertPose(context.Context, []repository.PoseRow) error
	BatchInsertBattery(context.Context, []repository.BatteryRow) error
	BatchInsertStatus(context.Context, []repository.StatusRow) error
	BatchInsertAck(context.Context, []repository.AckRow) error
}

type DeadLetter interface {
	SendToDLQ(context.Context, string, []byte, string) error
}

// Handler는 한 파티션의 배치를 저장한다. 실패한 배치는 호출자가 그대로 재시도한다.
type Handler struct {
	repo Store
	dlq  DeadLetter
}

func New(repo Store, dlq DeadLetter) *Handler { return &Handler{repo: repo, dlq: dlq} }

func (h *Handler) Process(ctx context.Context, msgs []kafkapkg.Message) error {
	if len(msgs) == 0 {
		return nil
	}
	var poses []repository.PoseRow
	var batteries []repository.BatteryRow
	var statuses []repository.StatusRow
	var acks []repository.AckRow
	for _, msg := range msgs {
		var err error
		switch msg.Topic {
		case "telemetry.pose":
			var row repository.PoseRow
			row, err = parsePose(msg.Payload)
			if err == nil {
				poses = append(poses, row)
			}
		case "telemetry.battery":
			var row repository.BatteryRow
			row, err = parseBattery(msg.Payload)
			if err == nil {
				batteries = append(batteries, row)
			}
		case "telemetry.status":
			var row repository.StatusRow
			row, err = parseStatus(msg.Payload)
			if err == nil {
				statuses = append(statuses, row)
			}
		case "ack.robot":
			var row repository.AckRow
			row, err = parseAck(msg.Payload)
			if err == nil {
				acks = append(acks, row)
			}
		default:
			return fmt.Errorf("unknown topic: %s", msg.Topic)
		}
		if err != nil {
			if err = h.dlq.SendToDLQ(ctx, msg.Topic, msg.Payload, err.Error()); err != nil {
				return err
			}
		}
	}
	switch msgs[0].Topic {
	case "telemetry.pose":
		return h.repo.BatchInsertPose(ctx, poses)
	case "telemetry.battery":
		return h.repo.BatchInsertBattery(ctx, batteries)
	case "telemetry.status":
		return h.repo.BatchInsertStatus(ctx, statuses)
	case "ack.robot":
		return h.repo.BatchInsertAck(ctx, acks)
	}
	return nil
}

func parsePose(payload []byte) (repository.PoseRow, error) {
	var env telemetryEnvelope
	if err := json.Unmarshal(payload, &env); err != nil {
		return repository.PoseRow{}, fmt.Errorf("envelope 파싱 실패: %w", err)
	}
	var data struct {
		X float64 `json:"x"`
		Y float64 `json:"y"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil {
		return repository.PoseRow{}, fmt.Errorf("pose data 파싱 실패: %w", err)
	}
	ts, err := time.Parse(time.RFC3339Nano, env.Timestamp)
	if err != nil {
		return repository.PoseRow{}, fmt.Errorf("timestamp 파싱 실패: %w", err)
	}
	return repository.PoseRow{
		EventID:   env.EventID,
		Timestamp: ts,
		RobotID:   env.RobotID,
		RuntimeID: env.RuntimeID,
		X:         data.X,
		Y:         data.Y,
	}, nil
}

func parseBattery(payload []byte) (repository.BatteryRow, error) {
	var env telemetryEnvelope
	if err := json.Unmarshal(payload, &env); err != nil {
		return repository.BatteryRow{}, fmt.Errorf("envelope 파싱 실패: %w", err)
	}
	var data struct {
		Level float64 `json:"level"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil {
		return repository.BatteryRow{}, fmt.Errorf("battery data 파싱 실패: %w", err)
	}
	ts, err := time.Parse(time.RFC3339Nano, env.Timestamp)
	if err != nil {
		return repository.BatteryRow{}, fmt.Errorf("timestamp 파싱 실패: %w", err)
	}
	return repository.BatteryRow{
		EventID:   env.EventID,
		Timestamp: ts,
		RobotID:   env.RobotID,
		RuntimeID: env.RuntimeID,
		Level:     data.Level,
	}, nil
}

func parseStatus(payload []byte) (repository.StatusRow, error) {
	var env telemetryEnvelope
	if err := json.Unmarshal(payload, &env); err != nil {
		return repository.StatusRow{}, fmt.Errorf("envelope 파싱 실패: %w", err)
	}
	var data struct {
		State string `json:"state"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil {
		return repository.StatusRow{}, fmt.Errorf("status data 파싱 실패: %w", err)
	}
	ts, err := time.Parse(time.RFC3339Nano, env.Timestamp)
	if err != nil {
		return repository.StatusRow{}, fmt.Errorf("timestamp 파싱 실패: %w", err)
	}
	return repository.StatusRow{
		EventID:   env.EventID,
		Timestamp: ts,
		RobotID:   env.RobotID,
		RuntimeID: env.RuntimeID,
		State:     data.State,
	}, nil
}

func parseAck(payload []byte) (repository.AckRow, error) {
	var env ackEnvelope
	if err := json.Unmarshal(payload, &env); err != nil {
		return repository.AckRow{}, fmt.Errorf("ack 파싱 실패: %w", err)
	}
	switch env.Status {
	case "accepted", "done", "failed":
	default:
		return repository.AckRow{}, fmt.Errorf("지원하지 않는 ack status: %q", env.Status)
	}
	ts, err := time.Parse(time.RFC3339Nano, env.Timestamp)
	if err != nil {
		return repository.AckRow{}, fmt.Errorf("timestamp 파싱 실패: %w", err)
	}
	if env.EventID != "" {
		if !validUUID(env.EventID) || !validUUID(env.CmdID) || env.FleetID == "" {
			return repository.AckRow{}, fmt.Errorf("invalid durable Ack identity")
		}
	}
	return repository.AckRow{
		EventID: env.EventID, FleetID: env.FleetID,
		CmdID:     env.CmdID,
		Timestamp: ts,
		RobotID:   env.RobotID,
		Status:    env.Status,
		Message:   env.Message,
	}, nil
}

func validUUID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for index, char := range value {
		if index == 8 || index == 13 || index == 18 || index == 23 {
			if char != '-' {
				return false
			}
			continue
		}
		if !(char >= '0' && char <= '9' || char >= 'a' && char <= 'f') {
			return false
		}
	}
	return true
}
