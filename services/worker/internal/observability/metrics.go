package observability

import (
	"context"
	"errors"
	"net/http"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Metrics는 Worker 처리 경로의 Prometheus 지표를 관리한다.
type Metrics struct {
	pauseMu          sync.Mutex
	lastPause        map[string]time.Time
	pauseSeconds     *prometheus.CounterVec
	lag              *prometheus.GaugeVec
	registry         *prometheus.Registry
	queue            *prometheus.GaugeVec
	controls         *prometheus.CounterVec
	messagesReceived *prometheus.CounterVec
	batchFlushes     *prometheus.CounterVec
	batchSize        *prometheus.HistogramVec
	batchDuration    *prometheus.HistogramVec
	batchBufferDepth *prometheus.GaugeVec
	offsetCommits    *prometheus.CounterVec
	dlqPublishes     *prometheus.CounterVec
}

// NewMetrics는 독립 registry와 Worker 지표를 생성한다.
func NewMetrics() *Metrics {
	m := &Metrics{
		registry:     prometheus.NewRegistry(),
		lastPause:    make(map[string]time.Time),
		pauseSeconds: prometheus.NewCounterVec(prometheus.CounterOpts{Name: "worker_partition_paused_seconds_total", Help: "Paused partition time"}, []string{"lane"}),
		lag:          prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "worker_partition_lag", Help: "Fetched high watermark minus last committed position"}, []string{"topic", "partition"}),
		queue:        prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "worker_pending", Help: "Pending reservations and paused partitions"}, []string{"lane", "kind"}),
		controls:     prometheus.NewCounterVec(prometheus.CounterOpts{Name: "worker_control_total", Help: "Backpressure control events"}, []string{"lane", "event"}),
		messagesReceived: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Namespace: "worker",
				Name:      "messages_received_total",
				Help:      "Kafka에서 수신한 메시지 수",
			},
			[]string{"topic"},
		),
		batchFlushes: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Namespace: "worker",
				Name:      "batch_flush_total",
				Help:      "DB 배치 적재 시도 수",
			},
			[]string{"topic", "result"},
		),
		batchSize: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Namespace: "worker",
				Name:      "batch_size",
				Help:      "DB 배치 적재 시도당 메시지 수",
				Buckets:   []float64{1, 10, 25, 50, 100, 250, 500, 1000},
			},
			[]string{"topic", "result"},
		),
		batchDuration: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Namespace: "worker",
				Name:      "batch_duration_seconds",
				Help:      "DB 배치 적재 시도 시간",
				Buckets:   prometheus.DefBuckets,
			},
			[]string{"topic", "result"},
		),
		batchBufferDepth: prometheus.NewGaugeVec(
			prometheus.GaugeOpts{
				Namespace: "worker",
				Name:      "batch_buffer_depth",
				Help:      "현재 topic별 배치 버퍼 메시지 수",
			},
			[]string{"topic"},
		),
		offsetCommits: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Namespace: "worker",
				Name:      "offset_commit_total",
				Help:      "Kafka offset commit 시도 수",
			},
			[]string{"topic", "result"},
		),
		dlqPublishes: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Namespace: "worker",
				Name:      "dlq_publish_total",
				Help:      "DLQ 발행 시도 수",
			},
			[]string{"original_topic", "result"},
		),
	}

	m.registry.MustRegister(
		collectors.NewGoCollector(),
		collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}),
		m.queue, m.controls, m.pauseSeconds, m.lag,
		m.messagesReceived,
		m.batchFlushes,
		m.batchSize,
		m.batchDuration,
		m.batchBufferDepth,
		m.offsetCommits,
		m.dlqPublishes,
	)

	return m
}

// ObserveMessage는 Kafka 메시지 수신을 기록한다.
func (m *Metrics) ObserveMessage(topic string) {
	m.messagesReceived.WithLabelValues(topic).Inc()
}

// ObserveBatch는 DB 배치 적재 결과를 기록한다.
func (m *Metrics) ObserveBatch(topic string, size int, duration time.Duration, succeeded bool) {
	result := resultLabel(succeeded)
	m.batchFlushes.WithLabelValues(topic, result).Inc()
	m.batchSize.WithLabelValues(topic, result).Observe(float64(size))
	m.batchDuration.WithLabelValues(topic, result).Observe(duration.Seconds())
}

// SetBatchBufferDepth는 topic별 배치 버퍼 크기를 기록한다.
func (m *Metrics) SetBatchBufferDepth(topic string, depth int) {
	m.batchBufferDepth.WithLabelValues(topic).Set(float64(depth))
}

// ObserveOffsetCommit은 Kafka offset commit 결과를 기록한다.
func (m *Metrics) ObserveOffsetCommit(topic string, succeeded bool) {
	m.offsetCommits.WithLabelValues(topic, resultLabel(succeeded)).Inc()
}

// ObserveDLQPublish는 DLQ 발행 결과를 기록한다.
func (m *Metrics) ObserveDLQPublish(originalTopic string, succeeded bool) {
	m.dlqPublishes.WithLabelValues(originalTopic, resultLabel(succeeded)).Inc()
}

// Handler는 metrics와 health endpoint를 제공한다.
func (m *Metrics) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{}))
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	return mux
}

// Serve는 context가 끝날 때까지 metrics HTTP 서버를 실행한다.
func (m *Metrics) Serve(ctx context.Context, address string) error {
	server := &http.Server{
		Addr:              address,
		Handler:           m.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
	}
	errCh := make(chan error, 1)
	go func() {
		errCh <- server.ListenAndServe()
	}()

	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return server.Shutdown(shutdownCtx)
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}

func resultLabel(succeeded bool) string {
	if succeeded {
		return "success"
	}
	return "failure"
}

func (m *Metrics) SetQueue(lane string, count, bytes, paused int) {
	m.pauseMu.Lock()
	now := time.Now()
	if last, ok := m.lastPause[lane]; ok {
		m.pauseSeconds.WithLabelValues(lane).Add(now.Sub(last).Seconds() * float64(paused))
	}
	m.lastPause[lane] = now
	m.pauseMu.Unlock()
	m.queue.WithLabelValues(lane, "messages").Set(float64(count))
	m.queue.WithLabelValues(lane, "reserved_bytes").Set(float64(bytes))
	m.queue.WithLabelValues(lane, "paused_partitions").Set(float64(paused))
}
func (m *Metrics) ObserveControl(lane, event string) { m.controls.WithLabelValues(lane, event).Inc() }

func (m *Metrics) SetLag(topic, partition string, lag int64) {
	if lag < 0 {
		lag = 0
	}
	m.lag.WithLabelValues(topic, partition).Set(float64(lag))
}
