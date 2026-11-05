package config

import "testing"

func TestDefaultsAndInvalidLimits(t *testing.T) {
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Telemetry.Messages != 4096 || cfg.Ack.Concurrency != 1 || cfg.Telemetry.Bytes != 64<<20 {
		t.Fatal("defaults changed")
	}
	for _, tc := range []struct{ key, value string }{
		{"BATCH_SIZE", "0"}, {"BATCH_FLUSH_MS", "-1"}, {"KAFKA_TOPICS", "unknown"}, {"KAFKA_TOPICS", "ack.robot,ack.robot"},
		{"ACK_PENDING_BYTES", "1"}, {"DB_OPERATION_TIMEOUT_MS", "0"}, {"MESSAGE_MAX_BYTES", "999999999999999999999"},
		{"SHUTDOWN_TIMEOUT_MS", "20001"}, {"ACK_KAFKA_QUEUE_KIB", "1023"}, {"TELEMETRY_KAFKA_QUEUE_KIB", "1023"},
		{"TELEMETRY_DB_CONCURRENCY", "1000"}, {"BATCH_BYTES", "100"},
	} {
		t.Run(tc.key+tc.value, func(t *testing.T) {
			t.Setenv(tc.key, tc.value)
			if _, err := Load(); err == nil {
				t.Fatal("invalid config accepted")
			}
		})
	}
}

func TestTimeoutAndReceiveBoundaries(t *testing.T) {
	t.Setenv("SHUTDOWN_TIMEOUT_MS", "20000")
	t.Setenv("DB_OPERATION_TIMEOUT_MS", "60000")
	t.Setenv("ACK_KAFKA_QUEUE_KIB", "1024")
	if _, err := Load(); err != nil {
		t.Fatalf("valid boundary rejected: %v", err)
	}
}
