package handler

import (
	"encoding/json"
	"testing"
)

func TestParseAckStatus(t *testing.T) {
	for _, status := range []string{"accepted", "done", "failed", "", "unknown", "DONE"} {
		t.Run(status, func(t *testing.T) {
			payload, err := json.Marshal(ackEnvelope{
				CmdID:     "00000000-0000-0000-0000-000000000001",
				Timestamp: "2026-10-28T00:00:00Z", RobotID: "robot-1", Status: status,
			})
			if err != nil {
				t.Fatal(err)
			}
			row, err := parseAck(payload)
			valid := status == "accepted" || status == "done" || status == "failed"
			if valid && (err != nil || row.Status != status) {
				t.Fatalf("valid status %q: row=%+v err=%v", status, row, err)
			}
			if !valid && err == nil {
				t.Fatalf("invalid status %q must be rejected before batching", status)
			}
		})
	}
}

func TestParseAckMalformedPayload(t *testing.T) {
	for _, payload := range []string{`{`, `{"status":"done","timestamp":"invalid"}`} {
		if _, err := parseAck([]byte(payload)); err == nil {
			t.Fatalf("payload %s must fail", payload)
		}
	}
}
