package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/sor999/robot/services/worker-go/internal/config"
	"github.com/sor999/robot/services/worker-go/internal/handler"
	kafkapkg "github.com/sor999/robot/services/worker-go/internal/kafka"
	"github.com/sor999/robot/services/worker-go/internal/repository"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("설정 로드 실패: %v", err)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	// DB 연결 풀 초기화
	pool, err := pgxpool.New(ctx, cfg.DBDSN)
	if err != nil {
		log.Fatalf("DB 연결 실패: %v", err)
	}
	defer pool.Close()

	if err := pool.Ping(ctx); err != nil {
		log.Fatalf("DB Ping 실패: %v", err)
	}
	log.Println("[Main] DB 연결 성공")

	// 테이블 자동 생성 (개발 환경용)
	repo := repository.New(pool)
	if err := repository.Migrate(ctx, pool); err != nil {
		log.Fatalf("DB 마이그레이션 실패: %v", err)
	}
	log.Println("[Main] DB 마이그레이션 완료")

	// Kafka Consumer 초기화
	consumer, err := kafkapkg.NewConsumer(cfg.KafkaBroker, cfg.KafkaGroupID, cfg.KafkaTopics, cfg.DLQTopic)
	if err != nil {
		log.Fatalf("Kafka Consumer 초기화 실패: %v", err)
	}
	log.Printf("[Main] Kafka Consumer 시작 | broker=%s | topics=%v", cfg.KafkaBroker, cfg.KafkaTopics)

	// Kafka → 핸들러 메시지 채널
	msgCh := make(chan kafkapkg.Message, 5000)

	// Kafka Consumer 고루틴
	go consumer.Run(ctx, msgCh)

	// 핸들러 (배치 누적 + DB 플러시) 고루틴 기동
	h := handler.New(repo, consumer, cfg.BatchSize, cfg.BatchFlushMs)
	log.Printf("[Main] Worker 시작 | batchSize=%d | flushMs=%d", cfg.BatchSize, cfg.BatchFlushMs)
	h.Run(ctx, msgCh)

	log.Println("[Main] Worker 정상 종료")
}
