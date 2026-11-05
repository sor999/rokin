package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/sor999/robot/services/worker/internal/config"
	"github.com/sor999/robot/services/worker/internal/handler"
	kafkapkg "github.com/sor999/robot/services/worker/internal/kafka"
	"github.com/sor999/robot/services/worker/internal/observability"
	"github.com/sor999/robot/services/worker/internal/repository"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("설정 로드 실패: %v", err)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	shutdownDone := make(chan struct{})
	defer close(shutdownDone)
	go func() {
		select {
		case <-shutdownDone:
			return
		case <-ctx.Done():
		}
		timer := time.NewTimer(cfg.Telemetry.ShutdownTimeout)
		defer timer.Stop()
		select {
		case <-shutdownDone:
			return
		case <-timer.C:
			log.Print("[Main] shutdown deadline reached; uncommitted records will replay")
			os.Exit(0)
		}
	}()
	metrics := observability.NewMetrics()
	go func() {
		log.Printf("[Metrics] Prometheus endpoint 시작 | address=%s", cfg.MetricsAddr)
		if err := metrics.Serve(ctx, cfg.MetricsAddr); err != nil {
			log.Printf("[Metrics] 서버 종료 오류: %v", err)
			cancel()
		}
	}()

	var wg sync.WaitGroup
	errors := make(chan error, 2)
	for _, lane := range []struct {
		name   string
		limits kafkapkg.Limits
		ack    bool
	}{
		{"telemetry", cfg.Telemetry, false}, {"ack", cfg.Ack, true},
	} {
		var topics []string
		for _, topic := range cfg.KafkaTopics {
			if (topic == "ack.robot") == lane.ack {
				topics = append(topics, topic)
			}
		}
		if len(topics) == 0 {
			continue
		}
		poolCfg, err := pgxpool.ParseConfig(cfg.DBDSN)
		if err != nil {
			log.Fatal(err)
		}
		poolCfg.MaxConns = int32(lane.limits.Concurrency)
		pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
		if err != nil {
			log.Fatal(err)
		}
		if err = repository.CheckSchema(ctx, pool); err != nil {
			pool.Close()
			log.Fatalf("Flyway schema: %v", err)
		}
		if os.Getenv("COMMAND_DURABLE_ENABLED") == "true" {
			if _, err = pool.Exec(ctx, "SELECT event_id, cmd_id FROM command_ack_receipt LIMIT 0"); err != nil {
				pool.Close()
				log.Fatal(err)
			}
		}
		consumer, err := kafkapkg.NewConsumer(cfg.KafkaBroker, cfg.KafkaGroupID, topics, cfg.DLQTopic, metrics, lane.limits)
		if err != nil {
			pool.Close()
			log.Fatal(err)
		}
		h := handler.New(repository.New(pool), consumer)
		engine := kafkapkg.NewEngine(consumer, lane.limits, h.Process, kafkapkg.MetricsAdapter{Metrics: metrics}, lane.name)
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer pool.Close()
			if err := engine.Run(ctx); err != nil {
				errors <- err
				cancel()
			}
		}()
	}
	wg.Wait()
	close(errors)
	failed := false
	for err := range errors {
		log.Printf("[Main] %v", err)
		failed = true
	}
	if failed {
		os.Exit(1)
	}
	log.Println("[Main] Worker 정상 종료")
}
