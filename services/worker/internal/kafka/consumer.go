package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
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
}

func NewConsumer(broker, groupID string, topics []string, dlqTopic string, metrics *observability.Metrics, limits Limits) (*Consumer, error) {
	kc, err := ck.NewConsumer(&ck.ConfigMap{
		"bootstrap.servers": broker, "group.id": groupID,
		"auto.offset.reset": "error", "enable.auto.commit": false, "enable.auto.offset.store": false,
		"go.application.rebalance.enable": true, "partition.assignment.strategy": "range",
		"queued.max.messages.kbytes": limits.NativeQueueKiB,
		"fetch.message.max.bytes":    limits.MessageBytes, "fetch.max.bytes": limits.NativeQueueKiB * 1024,
		"socket.timeout.ms": 3000, "session.timeout.ms": 10000,
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
	if err = kc.SubscribeTopics(topics, nil); err != nil {
		kc.Close()
		dlq.Close()
		return nil, err
	}
	return &Consumer{kc: kc, dlq: dlq, dlqTopic: dlqTopic, metrics: metrics, messageBytes: limits.MessageBytes}, nil
}

func partitions(ps []ck.TopicPartition) []Partition {
	out := make([]Partition, 0, len(ps))
	for _, p := range ps {
		out = append(out, Partition{*p.Topic, p.Partition})
	}
	return out
}
func topicPartition(p Partition) ck.TopicPartition {
	return ck.TopicPartition{Topic: &p.Topic, Partition: p.ID}
}

func (c *Consumer) Poll(ms int) Event {
	switch ev := c.kc.Poll(ms).(type) {
	case *ck.Message:
		c.metrics.ObserveMessage(*ev.TopicPartition.Topic)
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
			return p.Error
		}
		low, high, err := c.kc.QueryWatermarkOffsets(*p.Topic, p.Partition, 3000)
		if err != nil {
			return err
		}
		offset := int64(p.Offset)
		if p.Offset == ck.OffsetInvalid {
			offset = low
		} else if offset < low || offset > high {
			return fmt.Errorf("retained offset missing topic=%s partition=%d committed=%d range=[%d,%d]", *p.Topic, p.Partition, offset, low, high)
		}
		ps[i].Offset = ck.Offset(offset)
	}
	return c.kc.Assign(ps)
}
func (c *Consumer) Unassign() error { return c.kc.Unassign() }
func (c *Consumer) Pause(p Partition, paused bool) error {
	ps := []ck.TopicPartition{topicPartition(p)}
	if paused {
		return c.kc.Pause(ps)
	}
	return c.kc.Resume(ps)
}
func (c *Consumer) Seek(msg Message) error {
	p := topicPartition(Partition{msg.Topic, msg.Partition})
	p.Offset = ck.Offset(msg.Offset)
	return c.kc.Seek(p, 3000)
}
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
	c.metrics.ObserveOffsetCommit(p.Topic, err == nil)
	return err
}
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

func (a MetricsAdapter) Queue(lane string, count, bytes, paused int) {
	a.Metrics.SetQueue(lane, count, bytes, paused)
}
func (a MetricsAdapter) Retry(lane string)     { a.Metrics.ObserveControl(lane, "retry") }
func (a MetricsAdapter) Rebalance(lane string) { a.Metrics.ObserveControl(lane, "rebalance") }
func (a MetricsAdapter) Blocked(lane string)   { a.Metrics.ObserveControl(lane, "blocked") }
func (a MetricsAdapter) Batch(topic string, size int, duration time.Duration, ok bool) {
	a.Metrics.ObserveBatch(topic, size, duration, ok)
}
