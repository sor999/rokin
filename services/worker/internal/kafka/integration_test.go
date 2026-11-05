package kafka

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"

	ck "github.com/confluentinc/confluent-kafka-go/v2/kafka"
	"github.com/sor999/robot/services/worker/internal/observability"
)

func TestKafkaNewGroupReadsRetainedOldest(t *testing.T) {
	broker := os.Getenv("KAFKA_TEST_BROKER")
	if broker == "" {
		t.Skip("isolated KAFKA_TEST_BROKER required")
	}
	topic := fmt.Sprintf("worker-test-%d", time.Now().UnixNano())
	admin, err := ck.NewAdminClient(&ck.ConfigMap{"bootstrap.servers": broker})
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	results, err := admin.CreateTopics(ctx, []ck.TopicSpecification{{Topic: topic, NumPartitions: 1, ReplicationFactor: 1}})
	if err != nil || len(results) != 1 || results[0].Error.Code() != ck.ErrNoError {
		t.Fatalf("create: %v %v", results, err)
	}
	producer, err := ck.NewProducer(&ck.ConfigMap{"bootstrap.servers": broker, "acks": "all"})
	if err != nil {
		t.Fatal(err)
	}
	defer producer.Close()
	delivery := make(chan ck.Event, 1)
	for i := 0; i < 3; i++ {
		err = producer.Produce(&ck.Message{TopicPartition: ck.TopicPartition{Topic: &topic, Partition: 0}, Value: []byte("retained")}, delivery)
		if err != nil {
			t.Fatal(err)
		}
		select {
		case ev := <-delivery:
			if ev.(*ck.Message).TopicPartition.Error != nil {
				t.Fatal(ev)
			}
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		}
	}
	limits := Limits{Messages: 10, Bytes: 1 << 20, PartitionMessages: 10, PartitionBytes: 1 << 20, BatchSize: 3, BatchBytes: 1000, MessageBytes: 1000, Concurrency: 1, NativeQueueKiB: 1024, Flush: time.Millisecond, OperationTimeout: time.Second, ShutdownTimeout: time.Second}
	c, err := NewConsumer(broker, topic, []string{topic}, "dlq.telemetry", observability.NewMetrics(), limits)
	if err != nil {
		t.Fatal(err)
	}
	got := make(chan []Message, 10)
	runCtx, stop := context.WithTimeout(ctx, 15*time.Second)
	defer stop()
	engine := NewEngine(c, limits, func(_ context.Context, ms []Message) error { got <- ms; return nil }, noopObserver{}, "integration")
	finished := make(chan error, 1)
	go func() { finished <- engine.Run(runCtx) }()
	var offsets []int64
	for len(offsets) < 3 {
		select {
		case ms := <-got:
			for _, m := range ms {
				offsets = append(offsets, m.Offset)
			}
		case err := <-finished:
			t.Fatalf("consumer exited before reading retained messages: %v", err)
		case <-runCtx.Done():
			t.Fatal("retained messages not consumed", offsets)
		}
	}
	stop()
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
	if len(offsets) != 3 || offsets[0] != 0 || offsets[1] != 1 || offsets[2] != 2 {
		t.Fatal(offsets)
	}
}
