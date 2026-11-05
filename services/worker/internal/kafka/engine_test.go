package kafka

import (
	"context"
	"errors"
	ck "github.com/confluentinc/confluent-kafka-go/v2/kafka"
	"testing"
	"time"
)

type fakeDriver struct {
	paused   map[Partition]bool
	seeks    []Message
	commits  []int64
	failures int
	events   chan Event
	closed   bool
}

func (d *fakeDriver) Poll(ms int) Event {
	select {
	case e := <-d.events:
		return e
	case <-time.After(time.Duration(ms) * time.Millisecond):
		return Event{}
	}
}
func (d *fakeDriver) Assign([]Partition) error        { return nil }
func (d *fakeDriver) Unassign() error                 { return nil }
func (d *fakeDriver) Pause(p Partition, v bool) error { d.paused[p] = v; return nil }
func (d *fakeDriver) Seek(m Message) error            { d.seeks = append(d.seeks, m); return nil }
func (d *fakeDriver) Commit(_ Partition, v int64) error {
	d.commits = append(d.commits, v)
	if d.failures > 0 {
		d.failures--
		return errors.New("offline")
	}
	return nil
}
func (d *fakeDriver) Close() { d.closed = true }

type noopObserver struct{}

func (noopObserver) Queue(string, int, int, int)            {}
func (noopObserver) Retry(string)                           {}
func (noopObserver) Rebalance(string)                       {}
func (noopObserver) Blocked(string)                         {}
func (noopObserver) Batch(string, int, time.Duration, bool) {}

func setup(t *testing.T, process func(context.Context, []Message) error) (*Engine, *fakeDriver, Partition) {
	t.Helper()
	l := Limits{Messages: 4, Bytes: Reservation(16) * 4, PartitionMessages: 4, PartitionBytes: Reservation(16) * 4, BatchSize: 2, BatchBytes: 32, MessageBytes: 16, Concurrency: 1, Flush: time.Millisecond, OperationTimeout: time.Second, ShutdownTimeout: 30 * time.Millisecond}
	d := &fakeDriver{paused: map[Partition]bool{}, events: make(chan Event, 20)}
	e := NewEngine(d, l, process, noopObserver{}, "test")
	t.Cleanup(e.cancel)
	p := Partition{"telemetry.pose", 0}
	if err := e.assign([]Partition{p}); err != nil {
		t.Fatal(err)
	}
	return e, d, p
}
func message(p Partition, offset int64) Message {
	return Message{Topic: p.Topic, Partition: p.ID, Offset: offset, Payload: []byte("1234567890123456")}
}
func finish(t *testing.T, e *Engine) {
	t.Helper()
	select {
	case c := <-e.done:
		e.complete(c)
	case <-time.After(time.Second):
		t.Fatal("batch did not finish")
	}
}

func TestBoundsPauseResumeAndRewind(t *testing.T) {
	e, d, p := setup(t, func(context.Context, []Message) error { return nil })
	for i := 0; i < 4; i++ {
		if err := e.admit(message(p, int64(i))); err != nil {
			t.Fatal(err)
		}
	}
	if err := e.admit(message(p, 4)); err != nil {
		t.Fatal(err)
	}
	if e.count != 4 || e.bytes != e.limits.Bytes || len(d.seeks) != 1 {
		t.Fatalf("unbounded queue count=%d bytes=%d seeks=%d", e.count, e.bytes, len(d.seeks))
	}
	if err := e.controls(false); err != nil {
		t.Fatal(err)
	}
	if !d.paused[p] {
		t.Fatal("expected paused")
	}
	e.schedule(time.Now(), true)
	finish(t, e)
	e.controls(false)
	if d.paused[p] || e.count != 2 {
		t.Fatal("must resume at half usage")
	}
}

func TestByteBudgetIndependentOfCount(t *testing.T) {
	e, d, p := setup(t, func(context.Context, []Message) error { return nil })
	e.limits.Messages = 100
	e.limits.PartitionMessages = 100
	for i := 0; i < 10; i++ {
		e.admit(message(p, int64(i)))
	}
	if e.count != 4 || e.bytes > e.limits.Bytes || len(d.seeks) != 6 {
		t.Fatal("byte budget exceeded")
	}
}

func TestGappedOffsetsCommitRetryAndNoRegression(t *testing.T) {
	e, d, p := setup(t, func(context.Context, []Message) error { return nil })
	e.admit(message(p, 10))
	e.admit(message(p, 15))
	e.schedule(time.Now(), true)
	finish(t, e)
	d.failures = 1
	e.commits()
	e.commits()
	e.commits()
	if len(d.commits) != 2 || d.commits[0] != 16 || d.commits[1] != 16 {
		t.Fatalf("commits=%v", d.commits)
	}
	e.admit(message(p, 21))
	e.schedule(time.Now(), true)
	finish(t, e)
	e.commits()
	if d.commits[2] != 22 {
		t.Fatal(d.commits)
	}
}

func TestFailedBatchFrozenAndRetried(t *testing.T) {
	calls := make(chan []int64, 3)
	e, _, p := setup(t, func(_ context.Context, ms []Message) error {
		v := []int64{}
		for _, m := range ms {
			v = append(v, m.Offset)
		}
		calls <- v
		return errors.New("DB offline")
	})
	e.admit(message(p, 1))
	e.admit(message(p, 4))
	e.schedule(time.Now(), true)
	finish(t, e)
	e.admit(message(p, 8))
	e.admit(message(p, 10))
	state := e.states[p]
	if len(state.batch.messages) != 2 || e.count != 4 {
		t.Fatal("failed batch mutated")
	}
	e.schedule(time.Now(), true)
	if e.active != 0 {
		t.Fatal("backoff ignored")
	}
	state.batch.retryAt = time.Time{}
	e.schedule(time.Now(), true)
	finish(t, e)
	for i := 0; i < 2; i++ {
		v := <-calls
		if len(v) != 2 || v[0] != 1 || v[1] != 4 {
			t.Fatal(v)
		}
	}
	if state.desired != -1 {
		t.Fatal("committed failed batch")
	}
}

func TestRevokedWorkKeepsSlotAndIgnoresLateCompletion(t *testing.T) {
	started, release := make(chan struct{}), make(chan struct{})
	e, d, p := setup(t, func(context.Context, []Message) error { close(started); <-release; return nil })
	e.admit(message(p, 1))
	e.schedule(time.Now(), true)
	<-started
	old := e.states[p]
	e.assign([]Partition{p})
	e.admit(message(p, 8))
	e.schedule(time.Now(), true)
	if e.active != 1 || e.count != 2 || old.ctx.Err() == nil {
		t.Fatal("revoked work lost slot, reservation or cancellation")
	}
	close(release)
	finish(t, e)
	e.commits()
	if len(d.commits) != 0 || e.count != 1 || e.active != 0 {
		t.Fatal("stale completion affected new generation")
	}
}

func TestOversizedAndPermanentDLQBlockWithoutCommit(t *testing.T) {
	e, d, p := setup(t, func(context.Context, []Message) error { return &PermanentError{"DLQ too large"} })
	m := message(p, 0)
	m.Payload = make([]byte, 17)
	e.admit(m)
	e.controls(false)
	if !d.paused[p] || e.count != 0 {
		t.Fatal("oversized message not blocked")
	}
	e.assign([]Partition{p})
	e.admit(message(p, 1))
	e.schedule(time.Now(), true)
	finish(t, e)
	e.controls(false)
	e.commits()
	if !d.paused[p] || e.count != 1 || len(d.commits) != 0 {
		t.Fatal("permanent failure discarded")
	}
}

func TestShutdownCancelsUnfinishedAndClosesAfterCompletion(t *testing.T) {
	e, d, p := setup(t, func(ctx context.Context, _ []Message) error { <-ctx.Done(); return ctx.Err() })
	e.admit(message(p, 3))
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	started := time.Now()
	if err := e.Run(ctx); err != nil {
		t.Fatal(err)
	}
	if time.Since(started) > time.Second || !d.closed || len(d.commits) != 0 || e.active != 0 {
		t.Fatal("unsafe shutdown")
	}
}

func TestRoundRobinAndRawBatchLimit(t *testing.T) {
	e, _, p := setup(t, func(context.Context, []Message) error { return nil })
	q := Partition{p.Topic, 1}
	e.assign([]Partition{p, q})
	e.limits.BatchBytes = 16
	e.admit(message(p, 0))
	e.admit(message(p, 1))
	e.admit(message(q, 0))
	e.schedule(time.Now(), true)
	finish(t, e)
	if e.states[p].count != 1 {
		t.Fatal("raw batch limit ignored")
	}
	e.schedule(time.Now(), true)
	finish(t, e)
	if e.states[q].count != 0 {
		t.Fatal("partition starved")
	}
}

func TestRetainedOffsetNeverResetsExistingGroup(t *testing.T) {
	for _, tc := range []struct {
		committed ck.Offset
		want      int64
		fail      bool
	}{
		{ck.OffsetInvalid, 10, false}, {10, 10, false}, {20, 20, false}, {9, 0, true}, {21, 0, true},
	} {
		got, err := retainedOffset(tc.committed, 10, 20)
		if (err != nil) != tc.fail || (!tc.fail && got != tc.want) {
			t.Fatalf("offset=%d got=%d err=%v", tc.committed, got, err)
		}
	}
}

func TestRetrySuccessReleasesOnlyFrozenBatch(t *testing.T) {
	attempts := 0
	e, d, p := setup(t, func(context.Context, []Message) error {
		attempts++
		if attempts == 1 {
			return errors.New("offline")
		}
		return nil
	})
	e.admit(message(p, 1))
	e.admit(message(p, 4))
	e.schedule(time.Now(), true)
	finish(t, e)
	e.admit(message(p, 7))
	e.states[p].batch.retryAt = time.Time{}
	e.schedule(time.Now(), true)
	finish(t, e)
	e.commits()
	if e.count != 1 || e.states[p].count != 1 || len(d.commits) != 1 || d.commits[0] != 5 {
		t.Fatalf("count=%d commits=%v", e.count, d.commits)
	}
	e.schedule(time.Now(), true)
	finish(t, e)
	e.commits()
	if e.count != 0 || e.bytes != 0 || d.commits[1] != 8 {
		t.Fatal("recovery did not release reservations")
	}
}

func TestGlobalPressurePausesOtherPartitions(t *testing.T) {
	e, d, p := setup(t, func(context.Context, []Message) error { return nil })
	q := Partition{p.Topic, 1}
	e.assign([]Partition{p, q})
	for i := 0; i < 4; i++ {
		e.admit(message(p, int64(i)))
	}
	e.controls(false)
	if !d.paused[p] || !d.paused[q] {
		t.Fatal("global pressure did not pause whole lane")
	}
	e.schedule(time.Now(), true)
	finish(t, e)
	e.controls(false)
	if d.paused[p] || d.paused[q] {
		t.Fatal("half usage did not resume lane")
	}
}

func TestReassignmentClearsPreviousNativePause(t *testing.T) {
	e, d, p := setup(t, func(context.Context, []Message) error { return nil })
	for i := 0; i < 4; i++ {
		e.admit(message(p, int64(i)))
	}
	e.controls(false)
	if !d.paused[p] {
		t.Fatal("partition not paused before revoke")
	}
	if err := e.assign([]Partition{p}); err != nil {
		t.Fatal(err)
	}
	if d.paused[p] || e.count != 0 || e.bytes != 0 {
		t.Fatal("new assignment inherited native pause")
	}
	e.admit(message(p, 8))
	e.schedule(time.Now(), true)
	finish(t, e)
	e.commits()
	if len(d.commits) != 1 || d.commits[0] != 9 {
		t.Fatal("new assignment cannot progress", d.commits)
	}
}
