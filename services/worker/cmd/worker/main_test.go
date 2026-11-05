package main

import (
	"context"
	"sync/atomic"
	"testing"
	"time"
)

func TestShutdownWatchdogPreservesExitStatus(t *testing.T) {
	for _, failure := range []bool{false, true} {
		ctx, cancel := context.WithCancel(context.Background())
		done := make(chan struct{})
		exits := make(chan int, 1)
		var failed atomic.Bool
		go watchShutdown(ctx, done, time.Millisecond, &failed, func(code int) { exits <- code })
		failed.Store(failure)
		cancel()
		select {
		case code := <-exits:
			want := 0
			if failure {
				want = 1
			}
			if code != want {
				t.Fatalf("exit=%d want=%d", code, want)
			}
		case <-time.After(time.Second):
			t.Fatal("watchdog did not exit")
		}
	}
}

func TestCompletedShutdownDisarmsWatchdog(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	close(done)
	var failed atomic.Bool
	exited := false
	watchShutdown(ctx, done, time.Millisecond, &failed, func(int) { exited = true })
	if exited {
		t.Fatal("completed shutdown called exit")
	}
}
