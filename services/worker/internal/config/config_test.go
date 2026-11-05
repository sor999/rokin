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
