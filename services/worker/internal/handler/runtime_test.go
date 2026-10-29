package handler

import "testing"

func TestRuntimeIdentityParsing(t *testing.T) {
	payload := []byte(`{"event_id":"00000000-0000-0000-0000-000000000001","robot_id":"robot_4","timestamp":"2026-10-29T00:00:00Z","simulation_runtime_id":"current-runtime","data":{"x":1,"y":2,"level":90,"state":"idle"}}`)
	pose, err := parsePose(payload)
	if err != nil || pose.RuntimeID != "current-runtime" {
		t.Fatalf("pose runtime=%q err=%v", pose.RuntimeID, err)
	}
	battery, err := parseBattery(payload)
	if err != nil || battery.RuntimeID != "current-runtime" {
		t.Fatalf("battery runtime=%q err=%v", battery.RuntimeID, err)
	}
	status, err := parseStatus(payload)
	if err != nil || status.RuntimeID != "current-runtime" {
		t.Fatalf("status runtime=%q err=%v", status.RuntimeID, err)
	}
}
