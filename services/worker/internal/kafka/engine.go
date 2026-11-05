package kafka

import (
	"context"
	"errors"
	"fmt"
	"log"
	"time"
)

// Limits는 한 Consumer 경로의 전체 및 파티션별 예약 예산이다.
type Limits struct {
	Messages, Bytes, PartitionMessages, PartitionBytes int
	BatchSize, BatchBytes, MessageBytes, Concurrency   int
	Flush, OperationTimeout, ShutdownTimeout           time.Duration
	NativeQueueKiB                                     int
}

func Reservation(size int) int { return size*8 + 8192 }

type Partition struct {
	Topic string
	ID    int32
}
type Event struct {
	Message           *Message
	Assigned, Revoked []Partition
	Err               error
}

// Driver의 소비 및 커밋 메서드는 Engine 소유 루프에서만 호출한다.
type Driver interface {
	Poll(int) Event
	Assign([]Partition) error
	Unassign() error
	Pause(Partition, bool) error
	Seek(Message) error
	Commit(Partition, int64) error
	Close()
}

type Observer interface {
	Queue(string, int, int, int)
	Retry(string)
	Rebalance(string)
	Blocked(string)
	Batch(string, int, time.Duration, bool)
}

type batch struct {
	messages []Message
	bytes    int
	attempts int
	retryAt  time.Time
}
type partitionState struct {
	key                      Partition
	generation               uint64
	ctx                      context.Context
	cancel                   context.CancelFunc
	queue                    []Message
	bytes, count             int
	first                    time.Time
	batch                    *batch
	running, paused, blocked bool
	desired, committed       int64
}
type completion struct {
	state   *partitionState
	batch   *batch
	err     error
	elapsed time.Duration
}

// Engine는 큐, 할당 세대, 재시도 및 커밋을 단일 루프에서 관리한다.
type Engine struct {
	driver               Driver
	limits               Limits
	process              func(context.Context, []Message) error
	observer             Observer
	lane                 string
	states               map[Partition]*partitionState
	order                []Partition
	cursor               int
	generation           uint64
	count, bytes, active int
	done                 chan completion
	work                 context.Context
	cancel               context.CancelFunc
}

func NewEngine(driver Driver, limits Limits, process func(context.Context, []Message) error, observer Observer, lane string) *Engine {
	ctx, cancel := context.WithCancel(context.Background())
	return &Engine{driver: driver, limits: limits, process: process, observer: observer, lane: lane,
		states: make(map[Partition]*partitionState), done: make(chan completion, limits.Concurrency), work: ctx, cancel: cancel}
}

func (e *Engine) assign(keys []Partition) error {
	e.revoke()
	if err := e.driver.Assign(keys); err != nil {
		return err
	}
	e.generation++
	for _, key := range keys {
		ctx, cancel := context.WithCancel(e.work)
		e.states[key] = &partitionState{key: key, generation: e.generation, ctx: ctx, cancel: cancel, desired: -1, committed: -1}
	}
	e.order = append([]Partition(nil), keys...)
	e.cursor = 0
	e.observer.Rebalance(e.lane)
	return nil
}

func (e *Engine) revoke() {
	for _, p := range e.states {
		p.cancel()
		releasedCount, releasedBytes := p.count, p.bytes
		if p.running {
			releasedCount -= len(p.batch.messages)
			releasedBytes -= p.batch.bytes
		}
		e.count -= releasedCount
		e.bytes -= releasedBytes
		p.queue = nil
		if !p.running {
			p.batch = nil
		}
	}
	e.states = make(map[Partition]*partitionState)
	e.order = nil
}

func (e *Engine) admit(msg Message) error {
	key := Partition{msg.Topic, msg.Partition}
	p := e.states[key]
	if p == nil {
		return nil
	}
	if len(msg.Payload) > e.limits.MessageBytes {
		p.blocked = true
		e.observer.Blocked(e.lane)
		log.Printf("[Blocked] oversized message topic=%s partition=%d offset=%d bytes=%d", msg.Topic, msg.Partition, msg.Offset, len(msg.Payload))
		return nil
	}
	size := Reservation(len(msg.Payload))
	if p.blocked || e.count+1 > e.limits.Messages || e.bytes+size > e.limits.Bytes || p.count+1 > e.limits.PartitionMessages || p.bytes+size > e.limits.PartitionBytes {
		// 이미 Poll한 미수용 메시지는 Kafka에 남기고 같은 위치에서 다시 읽는다.
		return e.driver.Seek(msg)
	}
	if len(p.queue) == 0 {
		p.first = time.Now()
	}
	p.queue = append(p.queue, msg)
	p.count++
	p.bytes += size
	e.count++
	e.bytes += size
	return nil
}

func (e *Engine) controls(draining bool) error {
	max := Reservation(e.limits.MessageBytes)
	globalFull := e.count+1 > e.limits.Messages || e.bytes+max > e.limits.Bytes
	globalLow := e.count <= e.limits.Messages/2 && e.bytes <= e.limits.Bytes/2
	paused := 0
	for _, p := range e.states {
		full := draining || p.blocked || globalFull || p.count+1 > e.limits.PartitionMessages || p.bytes+max > e.limits.PartitionBytes
		low := globalLow && p.count <= e.limits.PartitionMessages/2 && p.bytes <= e.limits.PartitionBytes/2
		target := full || (p.paused && !low)
		if target != p.paused {
			if err := e.driver.Pause(p.key, target); err != nil {
				return err
			}
			p.paused = target
		}
		if p.paused {
			paused++
		}
	}
	e.observer.Queue(e.lane, e.count, e.bytes, paused)
	return nil
}

func (e *Engine) schedule(now time.Time, draining bool) {
	n := len(e.order)
	for checked := 0; checked < n && e.active < e.limits.Concurrency; checked++ {
		key := e.order[e.cursor%n]
		e.cursor = (e.cursor + 1) % n
		p := e.states[key]
		if p.running {
			continue
		}
		if p.batch == nil {
			if len(p.queue) == 0 {
				continue
			}
			if !draining && len(p.queue) < e.limits.BatchSize && now.Sub(p.first) < e.limits.Flush {
				raw := 0
				for _, m := range p.queue {
					raw += len(m.Payload)
				}
				if raw < e.limits.BatchBytes {
					continue
				}
			}
			b := &batch{}
			for _, msg := range p.queue {
				if len(b.messages) >= e.limits.BatchSize || (len(b.messages) > 0 && b.bytesRaw()+len(msg.Payload) > e.limits.BatchBytes) {
					break
				}
				b.messages = append(b.messages, msg)
				b.bytes += Reservation(len(msg.Payload))
			}
			remaining := append([]Message(nil), p.queue[len(b.messages):]...)
			p.queue = remaining
			p.first = now
			p.batch = b
		}
		b := p.batch
		if now.Before(b.retryAt) {
			continue
		}
		p.running = true
		e.active++
		go func(p *partitionState, b *batch) {
			ctx, cancel := context.WithTimeout(p.ctx, e.limits.OperationTimeout)
			started := time.Now()
			err := e.process(ctx, b.messages)
			cancel()
			e.done <- completion{p, b, err, time.Since(started)}
		}(p, b)
	}
}

func (b *batch) bytesRaw() int {
	n := 0
	for _, m := range b.messages {
		n += len(m.Payload)
	}
	return n
}

func (e *Engine) complete(c completion) {
	e.active--
	p, b := c.state, c.batch
	p.running = false
	e.observer.Batch(p.key.Topic, len(b.messages), c.elapsed, c.err == nil)
	current := e.states[p.key]
	if current != p || current.generation != p.generation {
		e.count -= len(b.messages)
		e.bytes -= b.bytes
		return
	}
	if c.err != nil {
		var permanent *PermanentError
		if errors.As(c.err, &permanent) {
			p.blocked = true
			e.observer.Blocked(e.lane)
			log.Printf("[Blocked] topic=%s partition=%d offset=%d err=%v", p.key.Topic, p.key.ID, b.messages[0].Offset, c.err)
			b.retryAt = time.Now().Add(100 * 365 * 24 * time.Hour)
			return
		}
		b.attempts++
		delay := time.Second
		for i := 1; i < b.attempts && delay < 5*time.Second; i++ {
			delay *= 2
		}
		if delay > 5*time.Second {
			delay = 5 * time.Second
		}
		b.retryAt = time.Now().Add(delay)
		e.observer.Retry(e.lane)
		log.Printf("[Retry] topic=%s partition=%d err=%v", p.key.Topic, p.key.ID, c.err)
		return
	}
	// 하나의 파티션은 하나의 배치만 수행하므로 실제 수신 순서의 완료 접두부다.
	p.desired = b.messages[len(b.messages)-1].Offset + 1
	p.count -= len(b.messages)
	p.bytes -= b.bytes
	e.count -= len(b.messages)
	e.bytes -= b.bytes
	p.batch = nil
}

func (e *Engine) commits() {
	for _, p := range e.states {
		if p.desired > p.committed {
			if err := e.driver.Commit(p.key, p.desired); err == nil {
				p.committed = p.desired
			}
		}
	}
}

func (e *Engine) Run(ctx context.Context) error {
	defer e.driver.Close()
	defer e.cancel()
	var deadline time.Time
	nextCommit := time.Now()
	for {
		draining := !deadline.IsZero()
		if !draining && ctx.Err() != nil {
			deadline = time.Now().Add(e.limits.ShutdownTimeout)
			draining = true
		}
		for {
			select {
			case c := <-e.done:
				e.complete(c)
			default:
				goto completed
			}
		}
	completed:
		if draining && (e.count == 0 || !time.Now().Before(deadline)) {
			e.cancel()
			// 취소된 작업도 종료까지 실행 한도에 포함하며 클라이언트를 먼저 닫지 않는다.
			for e.active > 0 {
				e.complete(<-e.done)
			}
			e.commits()
			return nil
		}
		if err := e.controls(draining); err != nil {
			return e.stop(err)
		}
		e.schedule(time.Now(), draining)
		if !time.Now().Before(nextCommit) {
			e.commits()
			nextCommit = time.Now().Add(time.Second)
		}
		ev := e.driver.Poll(20)
		if ev.Err != nil {
			return e.stop(ev.Err)
		}
		if ev.Revoked != nil {
			e.revoke()
			e.observer.Rebalance(e.lane)
			if err := e.driver.Unassign(); err != nil {
				return e.stop(err)
			}
		}
		if ev.Assigned != nil {
			if err := e.assign(ev.Assigned); err != nil {
				return e.stop(err)
			}
		}
		if ev.Message != nil {
			if draining {
				if err := e.driver.Seek(*ev.Message); err != nil {
					return e.stop(err)
				}
			} else if err := e.admit(*ev.Message); err != nil {
				return e.stop(err)
			}
		}
	}
}

func (e *Engine) stop(err error) error {
	e.cancel()
	e.revoke()
	for e.active > 0 {
		e.complete(<-e.done)
	}
	return fmt.Errorf("consumer lane %s: %w", e.lane, err)
}

type PermanentError struct{ Reason string }

func (e *PermanentError) Error() string { return e.Reason }
