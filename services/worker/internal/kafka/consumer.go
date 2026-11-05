package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strconv"
	"time"

	ck "github.com/confluentinc/confluent-kafka-go/v2/kafka"
	"github.com/sor999/robot/services/worker/internal/observability"
)

type Message struct {
	Topic     string
	Payload   []byte
	Partition int32
	Offset    int64
}
type Consumer struct {
	kc           *ck.Consumer
	dlq          *ck.Producer
	dlqTopic     string
	metrics      *observability.Metrics
	messageBytes int
	pending      *Event
	committed    map[Partition]int64
}

// NewConsumer는 수동 커밋과 제한된 native 큐를 사용하는 클라이언트를 생성한다.
func NewConsumer(broker, groupID string, topics []string, dlqTopic string, metrics *observability.Metrics, limits Limits) (*Consumer, error) {
	kc, err := ck.NewConsumer(&ck.ConfigMap{
		"bootstrap.servers": broker, "group.id": groupID,
		"auto.offset.reset": "error", "enable.auto.commit": false, "enable.auto.offset.store": false,
		"go.application.rebalance.enable": true, "partition.assignment.strategy": "range",
		"queued.max.messages.kbytes": limits.NativeQueueKiB,
		"fetch.message.max.bytes":    limits.MessageBytes, "fetch.max.bytes": limits.NativeQueueKiB * 1024,
		"receive.message.max.bytes": limits.NativeQueueKiB*1024 + 512,
		"socket.timeout.ms":         3000, "session.timeout.ms": 10000,
	})
	if err != nil {
		return nil, err
	}
	dlq, err := ck.NewProducer(&ck.ConfigMap{
		"bootstrap.servers": broker, "enable.idempotence": true, "acks": "all", "message.timeout.ms": 5000,
		"queue.buffering.max.messages": limits.Concurrency, "queue.buffering.max.kbytes": limits.Concurrency * limits.MessageBytes * 8 / 1024,
		"message.max.bytes": limits.MessageBytes,
	})
	if err != nil {
		kc.Close()
		return nil, err
	}
	c := &Consumer{committed: make(map[Partition]int64), kc: kc, dlq: dlq, dlqTopic: dlqTopic, metrics: metrics, messageBytes: limits.MessageBytes}
	// Poll 모드에서도 callback 안에서 할당을 명시해 클라이언트의 자동 할당을 막는다.
	if err = kc.SubscribeTopics(topics, func(kc *ck.Consumer, event ck.Event) error {
		switch ev := event.(type) {
		case ck.AssignedPartitions:
			c.pending = &Event{Assigned: partitions(ev.Partitions)}
		case ck.RevokedPartitions:
			c.pending = &Event{Revoked: partitions(ev.Partitions)}
		}
		// 검증된 시작 위치는 Engine이 같은 Poll 소유 루프에서 곧바로 적용한다.
		err := kc.Unassign()
		if err != nil {
			c.pending = &Event{Err: err}
		}
		return err
	}); err != nil {
		kc.Close()
		dlq.Close()
		return nil, err
	}
	return c, nil
}

// partitions는 Kafka 할당을 스케줄러의 파티션 식별자로 변환한다.
func partitions(ps []ck.TopicPartition) []Partition {
	out := make([]Partition, 0, len(ps))
	for _, p := range ps {
		out = append(out, Partition{*p.Topic, p.Partition})
	}
	return out
}

// topicPartition은 토픽과 파티션을 Kafka 클라이언트 식별자로 변환한다.
func topicPartition(p Partition) ck.TopicPartition {
	return ck.TopicPartition{Topic: &p.Topic, Partition: p.ID}
}

// Poll은 소유 루프에 메시지와 명시적인 재할당 이벤트를 전달한다.
func (c *Consumer) Poll(ms int) Event {
	event := c.kc.Poll(ms)
	if c.pending != nil {
		event := *c.pending
		c.pending = nil
		return event
	}
	switch ev := event.(type) {
	case *ck.Message:
		c.metrics.ObserveMessage(*ev.TopicPartition.Topic)
		c.observeLag(Partition{*ev.TopicPartition.Topic, ev.TopicPartition.Partition})
		return Event{Message: &Message{*ev.TopicPartition.Topic, ev.Value, ev.TopicPartition.Partition, int64(ev.TopicPartition.Offset)}}
	case ck.AssignedPartitions:
		return Event{Assigned: partitions(ev.Partitions)}
	case ck.RevokedPartitions:
		return Event{Revoked: partitions(ev.Partitions)}
	case ck.Error:
		if ev.Code() == ck.ErrOffsetOutOfRange || ev.Code() == ck.ErrAutoOffsetReset || ev.IsFatal() {
			return Event{Err: ev}
		}
		log.Printf("[Kafka] %v", ev)
	}
	return Event{}
}

// Assign은 보존 범위와 기존 커밋을 검증해 시작 위치를 설정한다.
func (c *Consumer) Assign(keys []Partition) error {
	ps := make([]ck.TopicPartition, 0, len(keys))
	for _, key := range keys {
		ps = append(ps, topicPartition(key))
	}
	committed, err := c.kc.Committed(ps, 3000)
	if err != nil {
		return err
	}
	for i, p := range committed {
		if p.Error != nil {
			missing, ok := p.Error.(ck.Error)
			if !ok || missing.Code() != ck.ErrNoOffset {
				return p.Error
			}
		}
		low, high, err := c.kc.QueryWatermarkOffsets(*p.Topic, p.Partition, 3000)
		if err != nil {
			return err
		}
		offset, err := retainedOffset(p.Offset, low, high)
		if err != nil {
			return fmt.Errorf("topic=%s partition=%d: %w", *p.Topic, p.Partition, err)
		}
		ps[i].Offset = ck.Offset(offset)
		c.committed[keys[i]] = offset
	}
	return c.kc.Assign(ps)
}

// Unassign은 이전 소유 파티션의 lag와 할당을 해제한다.
func (c *Consumer) Unassign() error {
	for key := range c.committed {
		c.metrics.SetLag(key.Topic, strconv.Itoa(int(key.ID)), 0)
	}
	c.committed = make(map[Partition]int64)
	return c.kc.Unassign()
}

// Pause는 해당 파티션의 소비를 중지하거나 재개한다.
func (c *Consumer) Pause(p Partition, paused bool) error {
	ps := []ck.TopicPartition{topicPartition(p)}
	if paused {
		return c.kc.Pause(ps)
	}
	return c.kc.Resume(ps)
}

// Seek는 수용하지 못한 메시지의 원래 위치로 소비 커서를 이동한다.
func (c *Consumer) Seek(msg Message) error {
	p := topicPartition(Partition{msg.Topic, msg.Partition})
	p.Offset = ck.Offset(msg.Offset)
	return c.kc.Seek(p, 3000)
}

// Commit은 다음 읽을 위치를 저장하고 파티션별 오류를 확인한다.
func (c *Consumer) Commit(p Partition, offset int64) error {
	tp := topicPartition(p)
	tp.Offset = ck.Offset(offset)
	result, err := c.kc.CommitOffsets([]ck.TopicPartition{tp})
	if err == nil {
		for _, r := range result {
			if r.Error != nil {
				err = r.Error
				break
			}
		}
	}
	if err == nil {
		c.committed[p] = offset
		c.observeLag(p)
	}
	c.metrics.ObserveOffsetCommit(p.Topic, err == nil)
	return err
}

// Close는 완료 작업 정리 후 Consumer와 DLQ Producer를 종료한다.
func (c *Consumer) Close() { c.kc.Close(); c.dlq.Close() }

// SendToDLQ는 전달 확인까지만 기다린다. 취소 후에도 delivery 채널은 producer가 소유한다.
func (c *Consumer) SendToDLQ(ctx context.Context, topic string, payload []byte, reason string) (err error) {
	defer func() { c.metrics.ObserveDLQPublish(topic, err == nil) }()
	value, err := json.Marshal(map[string]any{"original_topic": topic, "payload": string(payload), "reason": reason})
	if err != nil {
		return err
	}
	if len(value) > c.messageBytes {
		return &PermanentError{"DLQ envelope exceeds message limit"}
	}
	delivery := make(chan ck.Event, 1)
	if err = c.dlq.Produce(&ck.Message{TopicPartition: ck.TopicPartition{Topic: &c.dlqTopic, Partition: ck.PartitionAny}, Value: value}, delivery); err != nil {
		if ke, ok := err.(ck.Error); ok && ke.Code() == ck.ErrMsgSizeTooLarge {
			return &PermanentError{err.Error()}
		}
		return err
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case ev := <-delivery:
		msg, ok := ev.(*ck.Message)
		if !ok {
			return fmt.Errorf("unexpected DLQ event %T", ev)
		}
		if ke, ok := msg.TopicPartition.Error.(ck.Error); ok && ke.Code() == ck.ErrMsgSizeTooLarge {
			return &PermanentError{ke.Error()}
		}
		return msg.TopicPartition.Error
	}
}

// MetricsAdapter는 스케줄러의 예약량과 실행 결과를 수집한다.
type MetricsAdapter struct{ Metrics *observability.Metrics }

// Queue는 예약량과 중지 파티션 수를 기록한다.
func (a MetricsAdapter) Queue(lane string, count, bytes, paused int) {
	a.Metrics.SetQueue(lane, count, bytes, paused)
}

// Retry는 lane의 배치 재시도를 기록한다.
func (a MetricsAdapter) Retry(lane string) { a.Metrics.ObserveControl(lane, "retry") }

// Rebalance는 lane의 할당 변경을 기록한다.
func (a MetricsAdapter) Rebalance(lane string) { a.Metrics.ObserveControl(lane, "rebalance") }

// Blocked는 운영 확인이 필요한 파티션 중지를 기록한다.
func (a MetricsAdapter) Blocked(lane string) { a.Metrics.ObserveControl(lane, "blocked") }

// Batch는 DB 배치 실행 결과와 지연을 기록한다.
func (a MetricsAdapter) Batch(topic string, size int, duration time.Duration, ok bool) {
	a.Metrics.ObserveBatch(topic, size, duration, ok)
}

// observeLag는 캐시된 high watermark 기준의 커밋 지연을 기록한다.
func (c *Consumer) observeLag(p Partition) {
	if offset, ok := c.committed[p]; ok {
		_, high, err := c.kc.GetWatermarkOffsets(p.Topic, p.ID)
		if err == nil && high >= 0 {
			c.metrics.SetLag(p.Topic, strconv.Itoa(int(p.ID)), high-offset)
		}
	}
}

// retainedOffset은 새 group만 보존 시작점을 선택하고 기존 위치 유실은 거부한다.
func retainedOffset(committed ck.Offset, low, high int64) (int64, error) {
	if committed == ck.OffsetInvalid {
		return low, nil
	}
	offset := int64(committed)
	if offset < low || offset > high {
		return 0, fmt.Errorf("retained offset missing: committed=%d range=[%d,%d]", offset, low, high)
	}
	return offset, nil
}
