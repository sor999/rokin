package handler

import (
	"context"
	"errors"
	"testing"

	kafka "github.com/sor999/robot/services/worker/internal/kafka"
	"github.com/sor999/robot/services/worker/internal/repository"
)

type fakeStore struct {
	calls int
	rows  int
	err   error
}

func (s *fakeStore) BatchInsertPose(_ context.Context, r []repository.PoseRow) error {
	s.calls++
	s.rows += len(r)
	return s.err
}
func (*fakeStore) BatchInsertBattery(context.Context, []repository.BatteryRow) error { return nil }
func (*fakeStore) BatchInsertStatus(context.Context, []repository.StatusRow) error   { return nil }
func (*fakeStore) BatchInsertAck(context.Context, []repository.AckRow) error         { return nil }

type fakeDLQ struct {
	err   error
	calls int
}

func (d *fakeDLQ) SendToDLQ(context.Context, string, []byte, string) error { d.calls++; return d.err }

func TestDLQFailureRetainsBatchForRetry(t *testing.T) {
	s := &fakeStore{}
	d := &fakeDLQ{err: errors.New("broker down")}
	h := New(s, d)
	msgs := []kafka.Message{{Topic: "telemetry.pose", Payload: []byte("{")}}
	if err := h.Process(context.Background(), msgs); err == nil || s.calls != 0 {
		t.Fatal("unconfirmed DLQ completed")
	}
	d.err = nil
	if err := h.Process(context.Background(), msgs); err != nil || d.calls != 2 {
		t.Fatal("retry failed", err)
	}
}

func TestDBFailurePropagatesWithoutDiscard(t *testing.T) {
	s := &fakeStore{err: errors.New("DB down")}
	h := New(s, &fakeDLQ{})
	msgs := []kafka.Message{{Topic: "telemetry.pose", Payload: []byte(`{"event_id":"00000000-0000-0000-0000-000000000001","timestamp":"2026-11-05T00:00:00Z","robot_id":"robot_1","data":{"x":1,"y":2}}`)}}
	if err := h.Process(context.Background(), msgs); err == nil {
		t.Fatal("DB failure lost")
	}
	s.err = nil
	if err := h.Process(context.Background(), msgs); err != nil || s.rows != 2 {
		t.Fatal("batch was not replayable", err)
	}
}
