package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	KafkaBroker  string
	KafkaGroupID string
	KafkaTopics  []string

	DBDSN string

	BatchSize    int
	BatchFlushMs int

	DLQTopic string
}

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

	return &Config{
		KafkaBroker:  getEnv("KAFKA_BROKER_URL", "localhost:9092"),
		KafkaGroupID: getEnv("KAFKA_GROUP_ID", "telemetry-worker"),
		KafkaTopics:  topics,
		DBDSN:        dsn,
		BatchSize:    batchSize,
		BatchFlushMs: batchFlushMs,
		DLQTopic:     getEnv("DLQ_TOPIC", "dlq.telemetry"),
	}, nil
}

func getEnv(key, defaultVal string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultVal
}
