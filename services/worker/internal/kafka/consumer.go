package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"log"

	"github.com/confluentinc/confluent-kafka-go/v2/kafka"
)

// Message는 Kafka에서 읽은 메시지와 오프셋 정보를 포함한다.
type Message struct {
	Topic     string
	Payload   []byte
	Partition int32
	Offset    int64
}

// Consumer는 여러 토픽을 구독하는 Kafka Consumer 래퍼다.
type Consumer struct {
	kc       *kafka.Consumer
	dlq      *kafka.Producer
	dlqTopic string
}

func NewConsumer(broker, groupID string, topics []string, dlqTopic string) (*Consumer, error) {
	kc, err := kafka.NewConsumer(&kafka.ConfigMap{
		"bootstrap.servers":  broker,
		"group.id":           groupID,
		"auto.offset.reset":  "latest",
		"enable.auto.commit": false, // 수동 커밋 사용
	})
	if err != nil {
		return nil, fmt.Errorf("Kafka Consumer 생성 실패: %w", err)
	}
	if err := kc.SubscribeTopics(topics, nil); err != nil {
		return nil, fmt.Errorf("토픽 구독 실패: %w", err)
	}

	dlq, err := kafka.NewProducer(&kafka.ConfigMap{
		"bootstrap.servers": broker,
	})
	if err != nil {
		return nil, fmt.Errorf("DLQ Producer 생성 실패: %w", err)
	}

	return &Consumer{kc: kc, dlq: dlq, dlqTopic: dlqTopic}, nil
}

// Run은 Kafka 메시지를 읽어 ch 채널로 전달한다. ctx 취소 시 정상 종료한다.
func (c *Consumer) Run(ctx context.Context, ch chan<- Message) {
	defer c.kc.Close()
	defer c.dlq.Flush(3000)
	defer c.dlq.Close()

	for {
		select {
		case <-ctx.Done():
			log.Println("[Consumer] 종료 신호 수신, 컨슈머 종료")
			return
		default:
		}

		ev := c.kc.Poll(200)
		if ev == nil {
			continue
		}

		switch msg := ev.(type) {
		case *kafka.Message:
			ch <- Message{
				Topic:     *msg.TopicPartition.Topic,
				Payload:   msg.Value,
				Partition: msg.TopicPartition.Partition,
				Offset:    int64(msg.TopicPartition.Offset),
			}
		case kafka.Error:
			log.Printf("[Consumer] Kafka 오류: %v", msg)
		}
	}
}

// Commit은 특정 메시지의 오프셋을 커밋한다.
func (c *Consumer) Commit(topic string, partition int32, offset int64) error {
	_, err := c.kc.CommitOffsets([]kafka.TopicPartition{
		{
			Topic:     &topic,
			Partition: partition,
			Offset:    kafka.Offset(offset + 1), // 다음 읽을 위치
		},
	})
	return err
}

// SendToDLQ는 처리 실패한 메시지를 DLQ 토픽으로 전달한다.
// DLQ broker가 수신을 확인한 경우에만 nil을 반환한다.
func (c *Consumer) SendToDLQ(originalTopic string, payload []byte, reason string) error {
	envelope := map[string]any{
		"original_topic": originalTopic,
		"payload":        string(payload),
		"reason":         reason,
	}
	value, _ := json.Marshal(envelope)

	deliveryCh := make(chan kafka.Event, 1)
	defer close(deliveryCh)

	err := c.dlq.Produce(&kafka.Message{
		TopicPartition: kafka.TopicPartition{Topic: &c.dlqTopic, Partition: kafka.PartitionAny},
		Value:          value,
	}, deliveryCh)
	if err != nil {
		return fmt.Errorf("DLQ 발행 실패: %w", err)
	}

	event := <-deliveryCh
	msg, ok := event.(*kafka.Message)
	if !ok {
		return fmt.Errorf("DLQ delivery event 타입 오류: %T", event)
	}
	if msg.TopicPartition.Error != nil {
		return fmt.Errorf("DLQ delivery 실패: %w", msg.TopicPartition.Error)
	}

	log.Printf("[DLQ] 발행 완료 | topic=%s", c.dlqTopic)
	return nil
}
