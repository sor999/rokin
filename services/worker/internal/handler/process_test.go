package handler

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

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

type slowDLQ struct{ calls int }

func (d *slowDLQ) SendToDLQ(ctx context.Context, _ string, _ []byte, _ string) error {
	select {
	case <-time.After(300 * time.Millisecond):
		d.calls++
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

type deadlineStore struct{ fakeStore }

func (*deadlineStore) BatchInsertPose(ctx context.Context, _ []repository.PoseRow) error {
	<-ctx.Done()
	return ctx.Err()
}

func TestTimeoutAppliesPerDLQOperationRatherThanWholeBatch(t *testing.T) {
	d := &slowDLQ{}
	h := New(&fakeStore{}, d, time.Second)
	msgs := make([]kafka.Message, 4)
	for i := range msgs {
		msgs[i] = kafka.Message{Topic: "telemetry.pose", Payload: []byte("{")}
	}
	if err := h.Process(context.Background(), msgs); err != nil || d.calls != 4 {
		t.Fatalf("slow confirmed DLQ batch made no progress: calls=%d err=%v", d.calls, err)
	}
}

func TestDatabaseOperationDeadline(t *testing.T) {
	h := New(&deadlineStore{}, &fakeDLQ{}, 10*time.Millisecond)
	msgs := []kafka.Message{{Topic: "telemetry.pose", Payload: []byte(`{"event_id":"00000000-0000-0000-0000-000000000001","timestamp":"2026-11-05T00:00:00Z","robot_id":"robot_1","data":{"x":1,"y":2}}`)}}
	if err := h.Process(context.Background(), msgs); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("deadline missing: %v", err)
	}
}

func TestInvalidIdentitiesRouteToDLQ(t *testing.T) {
	for _, msg := range []kafka.Message{
		{Topic: "telemetry.pose", Payload: []byte(`{"event_id":"bad","robot_id":"robot_1","timestamp":"2026-11-05T00:00:00Z","data":{"x":0,"y":0}}`)},
		{Topic: "ack.robot", Payload: []byte(`{"cmd_id":"bad","robot_id":"robot_1","timestamp":"2026-11-05T00:00:00Z","status":"done"}`)},
	} {
		d := &fakeDLQ{}
		s := &fakeStore{}
		h := New(s, d)
		if err := h.Process(context.Background(), []kafka.Message{msg}); err != nil || d.calls != 1 || s.rows != 0 {
			t.Fatalf("malformed identity poisoned DB instead of DLQ: %v", err)
		}
	}
}

func TestDatabasePayloadConstraintsBeforeBatching(t *testing.T) {
	for _, tc := range []struct {
		topic string
		body  string
	}{
		{"telemetry.status", `{"event_id":"00000000-0000-0000-0000-000000000001","robot_id":"robot_1","timestamp":"2026-11-05T00:00:00Z","data":{"state":"` + strings.Repeat("s", 33) + `"}}`},
		{"telemetry.battery", `{"event_id":"00000000-0000-0000-0000-000000000001","robot_id":"robot_1","timestamp":"2026-11-05T00:00:00Z","data":{"level":1e40}}`},
		{"telemetry.pose", `{"event_id":"00000000-0000-0000-0000-000000000001","robot_id":"robot_1","timestamp":"2026-11-05T00:00:00Z","simulation_runtime_id":"` + strings.Repeat("r", 37) + `","data":{"x":0,"y":0}}`},
	} {
		d := &fakeDLQ{}
		h := New(&fakeStore{}, d)
		if err := h.Process(context.Background(), []kafka.Message{{Topic: tc.topic, Payload: []byte(tc.body)}}); err != nil || d.calls != 1 {
			t.Fatalf("constraint failure not sent to DLQ: %s %v", tc.topic, err)
		}
	}
}
