package config

import (
	"fmt"
	kafkapkg "github.com/sor999/robot/services/worker/internal/kafka"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	KafkaBroker  string
	KafkaGroupID string
	KafkaTopics  []string

	DBDSN string

	BatchSize    int
	BatchFlushMs int

	DLQTopic       string
	MetricsAddr    string
	Telemetry, Ack kafkapkg.Limits
}

// Load는 환경 설정을 읽고 소비 및 종료 제한을 검증한다.
func Load() (*Config, error) {
	batchSize, err := strconv.Atoi(getEnv("BATCH_SIZE", "100"))
	if err != nil {
		return nil, fmt.Errorf("BATCH_SIZE 파싱 실패: %w", err)
	}
	batchFlushMs, err := strconv.Atoi(getEnv("BATCH_FLUSH_MS", "500"))
	if err != nil {
		return nil, fmt.Errorf("BATCH_FLUSH_MS 파싱 실패: %w", err)
	}

	dsn := fmt.Sprintf(
		"host=%s port=%s user=%s password=%s dbname=%s sslmode=disable",
		getEnv("POSTGRES_HOST", "localhost"),
		getEnv("POSTGRES_PORT", "5432"),
		getEnv("POSTGRES_USER", "postgres"),
		getEnv("POSTGRES_PASSWORD", "postgres"),
		getEnv("POSTGRES_DB", "telemetry"),
	)

	topicsRaw := getEnv("KAFKA_TOPICS", "telemetry.pose,telemetry.battery,telemetry.status,ack.robot")
	topics := make([]string, 0)
	for _, t := range strings.Split(topicsRaw, ",") {
		if trimmed := strings.TrimSpace(t); trimmed != "" {
			topics = append(topics, trimmed)
		}
	}

	cfg := &Config{
		KafkaBroker:  getEnv("KAFKA_BROKER_URL", "localhost:9092"),
		KafkaGroupID: getEnv("KAFKA_GROUP_ID", "telemetry-worker"),
		KafkaTopics:  topics,
		DBDSN:        dsn,
		BatchSize:    batchSize,
		BatchFlushMs: batchFlushMs,
		DLQTopic:     getEnv("DLQ_TOPIC", "dlq.telemetry"),
		MetricsAddr:  getEnv("METRICS_ADDR", ":2112"),
	}
	known := map[string]bool{"telemetry.pose": true, "telemetry.battery": true, "telemetry.status": true, "ack.robot": true}
	seen := map[string]bool{}
	if len(topics) == 0 {
		return nil, fmt.Errorf("KAFKA_TOPICS must not be empty")
	}
	for _, topic := range topics {
		if !known[topic] || seen[topic] {
			return nil, fmt.Errorf("invalid or duplicate topic: %s", topic)
		}
		seen[topic] = true
	}
	if batchSize <= 0 || batchFlushMs <= 0 {
		return nil, fmt.Errorf("batch settings must be positive")
	}
	cfg.Telemetry, err = loadLimits("TELEMETRY", 4096, 64<<20, 4, 16<<10, batchSize, batchFlushMs)
	if err != nil {
		return nil, err
	}
	cfg.Ack, err = loadLimits("ACK", 1024, 16<<20, 1, 4<<10, batchSize, batchFlushMs)
	if err != nil {
		return nil, err
	}
	return cfg, nil
}

// getEnv는 비어 있지 않은 환경 값 또는 기본값을 반환한다.
func getEnv(key, defaultVal string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultVal
}

// loadLimits는 한 lane의 예약 예산과 Kafka 수신 한도를 함께 검증한다.
func loadLimits(prefix string, count, bytes, concurrency, native, batch, flush int) (kafkapkg.Limits, error) {
	l := kafkapkg.Limits{Messages: count, Bytes: bytes, PartitionMessages: 256, PartitionBytes: 16 << 20,
		BatchSize: batch, BatchBytes: 1 << 20, MessageBytes: 1 << 20, Concurrency: concurrency, NativeQueueKiB: native}
	fields := map[string]*int{
		prefix + "_PENDING_MESSAGES": &l.Messages, prefix + "_PENDING_BYTES": &l.Bytes,
		"PARTITION_PENDING_MESSAGES": &l.PartitionMessages, "PARTITION_PENDING_BYTES": &l.PartitionBytes,
		"BATCH_BYTES": &l.BatchBytes, "MESSAGE_MAX_BYTES": &l.MessageBytes,
		prefix + "_DB_CONCURRENCY": &l.Concurrency, prefix + "_KAFKA_QUEUE_KIB": &l.NativeQueueKiB,
	}
	for key, dest := range fields {
		value, err := strconv.Atoi(getEnv(key, strconv.Itoa(*dest)))
		if err != nil || value <= 0 || value > 1<<30 {
			return l, fmt.Errorf("invalid positive setting %s", key)
		}
		*dest = value
	}
	operation, shutdown := 5000, 20000
	for key, dest := range map[string]*int{"DB_OPERATION_TIMEOUT_MS": &operation, "SHUTDOWN_TIMEOUT_MS": &shutdown} {
		value, err := strconv.Atoi(getEnv(key, strconv.Itoa(*dest)))
		if err != nil || value <= 0 || value > 60000 || (key == "SHUTDOWN_TIMEOUT_MS" && value > 20000) {
			return l, fmt.Errorf("invalid timeout %s", key)
		}
		*dest = value
	}
	l.Flush = time.Duration(flush) * time.Millisecond
	l.OperationTimeout = time.Duration(operation) * time.Millisecond
	l.ShutdownTimeout = time.Duration(shutdown) * time.Millisecond
	if l.MessageBytes > l.BatchBytes || kafkapkg.Reservation(l.MessageBytes) > l.Bytes || kafkapkg.Reservation(l.MessageBytes) > l.PartitionBytes || l.Concurrency > 100 || batch > 100000 || flush > 60000 || l.NativeQueueKiB > 1<<20 || l.NativeQueueKiB*1024 < l.MessageBytes {
		return l, fmt.Errorf("inconsistent limits for %s", prefix)
	}
	return l, nil
}
