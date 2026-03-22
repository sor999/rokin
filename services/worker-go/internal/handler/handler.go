package handler

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	kafkapkg "github.com/sor999/robot/services/worker-go/internal/kafka"
	"github.com/sor999/robot/services/worker-go/internal/repository"
)

// 공통 Telemetry Envelope 구조체

type telemetryEnvelope struct {
	EventID   string          `json:"event_id"`
	Timestamp string          `json:"timestamp"`
	RobotID   string          `json:"robot_id"`
	Type      string          `json:"type"`
	Data      json.RawMessage `json:"data"`
}

type ackEnvelope struct {
	CmdID     string `json:"cmd_id"`
	Timestamp string `json:"timestamp"`
	RobotID   string `json:"robot_id"`
	Status    string `json:"status"`
	Message   string `json:"message"`
}

// Handler는 Kafka 메시지를 파싱해 배치 버퍼에 누적하고, 주기적으로 DB에 플러시한다.
// 토픽 유형별로 독립적인 Goroutine이 배치를 관리한다.
type Handler struct {
	repo         *repository.Repository
	consumer     *kafkapkg.Consumer
	batchSize    int
	flushTimeout time.Duration
	commitMu     sync.Mutex
	commitState  map[string]*partitionCommitState

	poseBuf    []repository.PoseRow
	batteryBuf []repository.BatteryRow
	statusBuf  []repository.StatusRow
	ackBuf     []repository.AckRow

	poseCh    chan kafkapkg.Message
	batteryCh chan kafkapkg.Message
	statusCh  chan kafkapkg.Message
	ackCh     chan kafkapkg.Message
}

type partitionCommitState struct {
	nextOffset  int64
	initialized bool
	completed   map[int64]struct{}
}

func New(
	repo *repository.Repository,
	consumer *kafkapkg.Consumer,
	batchSize int,
	batchFlushMs int,
) *Handler {
	return &Handler{
		repo:         repo,
		consumer:     consumer,
		batchSize:    batchSize,
		flushTimeout: time.Duration(batchFlushMs) * time.Millisecond,
		commitState:  make(map[string]*partitionCommitState),
		poseCh:       make(chan kafkapkg.Message, 1000),
		batteryCh:    make(chan kafkapkg.Message, 1000),
		statusCh:     make(chan kafkapkg.Message, 1000),
		ackCh:        make(chan kafkapkg.Message, 1000),
	}
}

// Run은 Kafka 메시지를 읽어 토픽별 채널로 팬아웃하고,
// 토픽별 배치 고루틴을 기동한다.
func (h *Handler) Run(ctx context.Context, msgCh <-chan kafkapkg.Message) {
	var wg sync.WaitGroup

	// 토픽별 배치 고루틴 기동
	wg.Add(4)
	go func() { defer wg.Done(); h.runPoseBatcher(ctx) }()
	go func() { defer wg.Done(); h.runBatteryBatcher(ctx) }()
	go func() { defer wg.Done(); h.runStatusBatcher(ctx) }()
	go func() { defer wg.Done(); h.runAckBatcher(ctx) }()

	// 팬아웃: 수신된 메시지를 토픽에 따라 채널로 분배
	for {
		select {
		case <-ctx.Done():
			// 채널을 닫아 배처들이 마지막 데이터를 플러시하고 종료하게 함
			close(h.poseCh)
			close(h.batteryCh)
			close(h.statusCh)
			close(h.ackCh)
			wg.Wait()
			return
		case msg, ok := <-msgCh:
			if !ok {
				// 입력 채널이 닫히면 모든 배처 채널 닫기
				close(h.poseCh)
				close(h.batteryCh)
				close(h.statusCh)
				close(h.ackCh)
				wg.Wait()
				return
			}
			h.registerMessage(msg)
			switch msg.Topic {
			case "telemetry.pose":
				h.poseCh <- msg
			case "telemetry.battery":
				h.batteryCh <- msg
			case "telemetry.status":
				h.statusCh <- msg
			case "ack.robot":
				h.ackCh <- msg
			}
		}
	}
}

func (h *Handler) registerMessage(msg kafkapkg.Message) {
	h.commitMu.Lock()
	defer h.commitMu.Unlock()

	key := commitKey(msg.Topic, msg.Partition)
	state, ok := h.commitState[key]
	if !ok {
		state = &partitionCommitState{
			nextOffset:  msg.Offset,
			initialized: true,
			completed:   make(map[int64]struct{}),
		}
		h.commitState[key] = state
		return
	}
	if !state.initialized || msg.Offset < state.nextOffset {
		state.nextOffset = msg.Offset
		state.initialized = true
	}
}

func (h *Handler) markProcessed(msg kafkapkg.Message) {
	h.commitMu.Lock()

	key := commitKey(msg.Topic, msg.Partition)
	state, ok := h.commitState[key]
	if !ok {
		state = &partitionCommitState{
			nextOffset:  msg.Offset,
			initialized: true,
			completed:   make(map[int64]struct{}),
		}
		h.commitState[key] = state
	}
	state.completed[msg.Offset] = struct{}{}

	commitOffset := state.nextOffset - 1
	nextOffset := state.nextOffset
	for state.initialized {
		if _, ok := state.completed[nextOffset]; !ok {
			break
		}
		commitOffset = nextOffset
		nextOffset++
	}

	if commitOffset >= 0 {
		for state.nextOffset <= commitOffset {
			delete(state.completed, state.nextOffset)
			state.nextOffset++
		}
	}
	h.commitMu.Unlock() // Unlock BEFORE network IO to prevent blocking all partitions

	if commitOffset >= 0 {
		if err := h.consumer.Commit(msg.Topic, msg.Partition, commitOffset); err != nil {
			log.Printf("[Commit] 오프셋 커밋 실패 | topic=%s partition=%d offset=%d err=%v", msg.Topic, msg.Partition, commitOffset, err)
			return
		}
		log.Printf("[Commit] 오프셋 커밋 완료 | topic=%s partition=%d offset=%d", msg.Topic, msg.Partition, commitOffset)
	}
}

func commitKey(topic string, partition int32) string {
	return fmt.Sprintf("%s:%d", topic, partition)
}

// runPoseBatcher는 pose 채널을 읽어 배치 누적 후 DB에 플러시한다.
func (h *Handler) runPoseBatcher(ctx context.Context) {
	ticker := time.NewTicker(h.flushTimeout)
	defer ticker.Stop()

	var buf []repository.PoseRow
	var msgs []kafkapkg.Message

	flush := func(isShutdown bool) {
		if len(buf) == 0 {
			return
		}

		flushCtx := ctx
		if isShutdown {
			// 종료 시 남은 데이터를 안전하게 플러시하기 위해 백그라운드 컨텍스트 타임아웃 사용
			var cancel context.CancelFunc
			flushCtx, cancel = context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
		}

		var err error
		for i := 0; i < 3; i++ {
			if err = h.repo.BatchInsertPose(flushCtx, buf); err == nil {
				log.Printf("[Batcher/pose] %d건 적재 완료", len(buf))
				for _, msg := range msgs {
					h.markProcessed(msg)
				}
				buf = buf[:0]
				msgs = msgs[:0]
				return
			}
			log.Printf("[Batcher/pose] DB 적재 실패 (재시도 %d/3): %v", i+1, err)
			time.Sleep(time.Duration(1<<i) * time.Second) // exponential backoff: 1s, 2s, 4s
		}
		log.Printf("[Batcher/pose] 치명적: DB 적재 최종 실패. 버퍼 유지.")
	}

	for {
		select {
		case msg, ok := <-h.poseCh:
			if !ok {
				flush(true)
				return
			}
			row, err := parsePose(msg.Payload)
			if err != nil {
				if dlqErr := h.consumer.SendToDLQ(msg.Topic, msg.Payload, err.Error()); dlqErr != nil {
					log.Printf("[Batcher/pose] DLQ 발행 실패: %v", dlqErr)
					continue
				}
				h.markProcessed(msg)
				continue
			}
			buf = append(buf, row)
			msgs = append(msgs, msg)
			if len(buf) >= h.batchSize {
				flush(false)
			}
		case <-ticker.C:
			flush(false)
		case <-ctx.Done():
			flush(true)
			return
		}
	}
}

// runBatteryBatcher는 battery 채널을 읽어 배치 누적 후 DB에 플러시한다.
func (h *Handler) runBatteryBatcher(ctx context.Context) {
	ticker := time.NewTicker(h.flushTimeout)
	defer ticker.Stop()

	var buf []repository.BatteryRow
	var msgs []kafkapkg.Message

	flush := func(isShutdown bool) {
		if len(buf) == 0 {
			return
		}
		flushCtx := ctx
		if isShutdown {
			var cancel context.CancelFunc
			flushCtx, cancel = context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
		}

		var err error
		for i := 0; i < 3; i++ {
			if err = h.repo.BatchInsertBattery(flushCtx, buf); err == nil {
				log.Printf("[Batcher/battery] %d건 적재 완료", len(buf))
				for _, msg := range msgs {
					h.markProcessed(msg)
				}
				buf = buf[:0]
				msgs = msgs[:0]
				return
			}
			log.Printf("[Batcher/battery] DB 적재 실패 (재시도 %d/3): %v", i+1, err)
			time.Sleep(time.Duration(1<<i) * time.Second)
		}
		log.Printf("[Batcher/battery] 치명적: DB 적재 최종 실패. 버퍼 유지.")
	}

	for {
		select {
		case msg, ok := <-h.batteryCh:
			if !ok {
				flush(true)
				return
			}
			row, err := parseBattery(msg.Payload)
			if err != nil {
				if dlqErr := h.consumer.SendToDLQ(msg.Topic, msg.Payload, err.Error()); dlqErr != nil {
					log.Printf("[Batcher/battery] DLQ 발행 실패: %v", dlqErr)
					continue
				}
				h.markProcessed(msg)
				continue
			}
			buf = append(buf, row)
			msgs = append(msgs, msg)
			if len(buf) >= h.batchSize {
				flush(false)
			}
		case <-ticker.C:
			flush(false)
		case <-ctx.Done():
			flush(true)
			return
		}
	}
}

// runStatusBatcher는 status 채널을 읽어 배치 누적 후 DB에 플러시한다.
func (h *Handler) runStatusBatcher(ctx context.Context) {
	ticker := time.NewTicker(h.flushTimeout)
	defer ticker.Stop()

	var buf []repository.StatusRow
	var msgs []kafkapkg.Message

	flush := func(isShutdown bool) {
		if len(buf) == 0 {
			return
		}
		flushCtx := ctx
		if isShutdown {
			var cancel context.CancelFunc
			flushCtx, cancel = context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
		}

		var err error
		for i := 0; i < 3; i++ {
			if err = h.repo.BatchInsertStatus(flushCtx, buf); err == nil {
				log.Printf("[Batcher/status] %d건 적재 완료", len(buf))
				for _, msg := range msgs {
					h.markProcessed(msg)
				}
				buf = buf[:0]
				msgs = msgs[:0]
				return
			}
			log.Printf("[Batcher/status] DB 적재 실패 (재시도 %d/3): %v", i+1, err)
			time.Sleep(time.Duration(1<<i) * time.Second)
		}
		log.Printf("[Batcher/status] 치명적: DB 적재 최종 실패. 버퍼 유지.")
	}

	for {
		select {
		case msg, ok := <-h.statusCh:
			if !ok {
				flush(true)
				return
			}
			row, err := parseStatus(msg.Payload)
			if err != nil {
				if dlqErr := h.consumer.SendToDLQ(msg.Topic, msg.Payload, err.Error()); dlqErr != nil {
					log.Printf("[Batcher/status] DLQ 발행 실패: %v", dlqErr)
					continue
				}
				h.markProcessed(msg)
				continue
			}
			buf = append(buf, row)
			msgs = append(msgs, msg)
			if len(buf) >= h.batchSize {
				flush(false)
			}
		case <-ticker.C:
			flush(false)
		case <-ctx.Done():
			flush(true)
			return
		}
	}
}

// runAckBatcher는 ack 채널을 읽어 배치 누적 후 DB에 플러시한다.
func (h *Handler) runAckBatcher(ctx context.Context) {
	ticker := time.NewTicker(h.flushTimeout)
	defer ticker.Stop()

	var buf []repository.AckRow
	var msgs []kafkapkg.Message

	flush := func(isShutdown bool) {
		if len(buf) == 0 {
			return
		}
		flushCtx := ctx
		if isShutdown {
			var cancel context.CancelFunc
			flushCtx, cancel = context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
		}

		var err error
		for i := 0; i < 3; i++ {
			if err = h.repo.BatchInsertAck(flushCtx, buf); err == nil {
				log.Printf("[Batcher/ack] %d건 적재 완료", len(buf))
				for _, msg := range msgs {
					h.markProcessed(msg)
				}
				buf = buf[:0]
				msgs = msgs[:0]
				return
			}
			log.Printf("[Batcher/ack] DB 적재 실패 (재시도 %d/3): %v", i+1, err)
			time.Sleep(time.Duration(1<<i) * time.Second)
		}
		log.Printf("[Batcher/ack] 치명적: DB 적재 최종 실패. 버퍼 유지.")
	}

	for {
		select {
		case msg, ok := <-h.ackCh:
			if !ok {
				flush(true)
				return
			}
			row, err := parseAck(msg.Payload)
			if err != nil {
				if dlqErr := h.consumer.SendToDLQ(msg.Topic, msg.Payload, err.Error()); dlqErr != nil {
					log.Printf("[Batcher/ack] DLQ 발행 실패: %v", dlqErr)
					continue
				}
				h.markProcessed(msg)
				continue
			}
			buf = append(buf, row)
			msgs = append(msgs, msg)
			if len(buf) >= h.batchSize {
				flush(false)
			}
		case <-ticker.C:
			flush(false)
		case <-ctx.Done():
			flush(true)
			return
		}
	}
}

// 파서 함수들

func parsePose(payload []byte) (repository.PoseRow, error) {
	var env telemetryEnvelope
	if err := json.Unmarshal(payload, &env); err != nil {
		return repository.PoseRow{}, fmt.Errorf("envelope 파싱 실패: %w", err)
	}
	var data struct {
		X float64 `json:"x"`
		Y float64 `json:"y"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil {
		return repository.PoseRow{}, fmt.Errorf("pose data 파싱 실패: %w", err)
	}
	ts, err := time.Parse(time.RFC3339Nano, env.Timestamp)
	if err != nil {
		return repository.PoseRow{}, fmt.Errorf("timestamp 파싱 실패: %w", err)
	}
	return repository.PoseRow{
		EventID:   env.EventID,
		Timestamp: ts,
		RobotID:   env.RobotID,
		X:         data.X,
		Y:         data.Y,
	}, nil
}

func parseBattery(payload []byte) (repository.BatteryRow, error) {
	var env telemetryEnvelope
	if err := json.Unmarshal(payload, &env); err != nil {
		return repository.BatteryRow{}, fmt.Errorf("envelope 파싱 실패: %w", err)
	}
	var data struct {
		Level float64 `json:"level"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil {
		return repository.BatteryRow{}, fmt.Errorf("battery data 파싱 실패: %w", err)
	}
	ts, err := time.Parse(time.RFC3339Nano, env.Timestamp)
	if err != nil {
		return repository.BatteryRow{}, fmt.Errorf("timestamp 파싱 실패: %w", err)
	}
	return repository.BatteryRow{
		EventID:   env.EventID,
		Timestamp: ts,
		RobotID:   env.RobotID,
		Level:     data.Level,
	}, nil
}

func parseStatus(payload []byte) (repository.StatusRow, error) {
	var env telemetryEnvelope
	if err := json.Unmarshal(payload, &env); err != nil {
		return repository.StatusRow{}, fmt.Errorf("envelope 파싱 실패: %w", err)
	}
	var data struct {
		State string `json:"state"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil {
		return repository.StatusRow{}, fmt.Errorf("status data 파싱 실패: %w", err)
	}
	ts, err := time.Parse(time.RFC3339Nano, env.Timestamp)
	if err != nil {
		return repository.StatusRow{}, fmt.Errorf("timestamp 파싱 실패: %w", err)
	}
	return repository.StatusRow{
		EventID:   env.EventID,
		Timestamp: ts,
		RobotID:   env.RobotID,
		State:     data.State,
	}, nil
}

func parseAck(payload []byte) (repository.AckRow, error) {
	var env ackEnvelope
	if err := json.Unmarshal(payload, &env); err != nil {
		return repository.AckRow{}, fmt.Errorf("ack 파싱 실패: %w", err)
	}
	ts, err := time.Parse(time.RFC3339Nano, env.Timestamp)
	if err != nil {
		return repository.AckRow{}, fmt.Errorf("timestamp 파싱 실패: %w", err)
	}
	return repository.AckRow{
		CmdID:     env.CmdID,
		Timestamp: ts,
		RobotID:   env.RobotID,
		Status:    env.Status,
		Message:   env.Message,
	}, nil
}
