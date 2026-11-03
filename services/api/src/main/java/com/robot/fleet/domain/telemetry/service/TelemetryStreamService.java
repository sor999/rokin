package com.robot.fleet.domain.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.telemetry.dto.TelemetryEvent;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TelemetryStreamService implements AutoCloseable {
    private final ObjectMapper mapper;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Counter broadcastCounter;
    private final Counter sendFailureCounter;
    private final DistributionSummary lag;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().daemon().name("sse-watchdog").unstarted(r));
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean monitoring = new AtomicBoolean();
    private final AtomicInteger writers = new AtomicInteger();
    @Value("${fleet.sse.max-connections:1000}") private int maxConnections = 1000;
    @Value("${fleet.sse.queue-capacity:32}") private int queueCapacity = 32;
    @Value("${fleet.sse.send-timeout-ms:5000}") private long sendTimeoutMs = 5000;

    public TelemetryStreamService(ObjectMapper mapper, MeterRegistry metrics) {
        this.mapper = mapper;
        broadcastCounter = metrics.counter("fleet.sse.broadcast");
        sendFailureCounter = metrics.counter("fleet.sse.send.failures");
        lag = DistributionSummary.builder("fleet.sse.event.lag").baseUnit("seconds")
                .publishPercentileHistogram().register(metrics);
        Gauge.builder("fleet.sse.connections", connections, Set::size).register(metrics);
        Gauge.builder("fleet.sse.writers", writers, AtomicInteger::get).register(metrics);
        Gauge.builder("fleet.sse.pending", connections,
                set -> set.stream().mapToInt(Connection::pending).sum()).register(metrics);
    }

    @jakarta.annotation.PostConstruct
    public void validateLimits() {
        if (maxConnections < 1 || queueCapacity < 1 || sendTimeoutMs < 1) throw new IllegalArgumentException("Invalid SSE limits");
    }

    public SseEmitter createEmitter() { return registerEmitter(new SseEmitter(0L)); }

    synchronized SseEmitter registerEmitter(SseEmitter emitter) {
        if (stopped.get() || writers.get() >= maxConnections) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "실시간 연결 상한에 도달했습니다.");
        }
        if (monitoring.compareAndSet(false, true)) watchdog.scheduleAtFixedRate(this::checkSlowConnections, 100, 100, TimeUnit.MILLISECONDS);
        Connection connection = new Connection(emitter);
        writers.incrementAndGet();
        connections.add(connection);
        emitter.onCompletion(() -> connection.finish(null));
        emitter.onTimeout(() -> connection.finish(new IOException("SSE timeout")));
        emitter.onError(connection::finish);
        connection.worker = Thread.ofVirtual().name("sse-writer").unstarted(connection::write);
        connection.worker.start();
        return emitter;
    }

    public void broadcast(TelemetryEvent event) {
        if (event.occurredAt() == null) throw new IllegalArgumentException("Telemetry timestamp is required");
        String data;
        try { data = mapper.writeValueAsString(event); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid telemetry", error); }
        broadcastCounter.increment();
        lag.record(Math.max(0, Duration.between(event.occurredAt(), OffsetDateTime.now()).toMillis()) / 1000.0);
        String key = event.robotId() + ":" + event.type();
        for (Connection connection : connections) connection.offerEvent(event, key, data);
    }

    @Scheduled(fixedRateString = "${fleet.sse-ping-ms:15000}")
    public void sendPing() {
        for (Connection connection : connections) connection.offer("ping", null);
    }

    private void checkSlowConnections() {
        long limit = TimeUnit.MILLISECONDS.toNanos(sendTimeoutMs);
        long now = System.nanoTime();
        for (Connection connection : connections) {
            long sending = connection.sendingSince;
            if (sending != 0 && now - sending > limit) connection.finish(new IOException("Slow SSE client"));
        }
    }

    public void reset() {
        for (Connection connection : connections) connection.finish(new IOException("Realtime connection reset"));
    }

    @PreDestroy public void close() { stopped.set(true); reset(); watchdog.shutdownNow(); }

    private final class Connection {
        private final SseEmitter emitter;
        private final LinkedHashMap<String, String> queue = new LinkedHashMap<>();
        private final LinkedHashMap<String, String> offline = new LinkedHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger tasks = new AtomicInteger(2);
        private volatile long sendingSince;
        private volatile Thread worker;

        private Connection(SseEmitter emitter) { this.emitter = emitter; }
        private synchronized int pending() { return queue.size(); }

        private synchronized void offerEvent(TelemetryEvent event, String key, String data) {
            if (closed.get()) return;
            if (event.type().equals("offline")) {
                if (data.equals(offline.put(event.robotId(), data))) return;
                if (offline.size() > 1024) offline.remove(offline.keySet().iterator().next());
            } else offline.remove(event.robotId());
            offer(key, data);
        }

        private void offer(String key, String data) {
            boolean full;
            synchronized (this) {
                if (closed.get()) return;
                queue.remove(key);
                full = queue.size() >= queueCapacity;
                if (!full) { queue.put(key, data); notifyAll(); }
            }
            if (full && data != null) finish(new IOException("SSE buffer full"));
        }

        private void write() {
            try {
                while (!closed.get()) {
                    String data;
                    synchronized (this) {
                        while (queue.isEmpty() && !closed.get()) wait();
                        if (closed.get()) return;
                        var entry = queue.entrySet().iterator();
                        data = entry.next().getValue();
                        entry.remove();
                    }
                    sendingSince = System.nanoTime();
                    try {
                        emitter.send(data == null ? SseEmitter.event().comment("ping") : SseEmitter.event().name("robot_update").data(data));
                    } finally { sendingSince = 0; }
                }
            } catch (Exception error) { finish(error); }
            finally { taskFinished(); }
        }

        private void taskFinished() { if (tasks.decrementAndGet() == 0) writers.decrementAndGet(); }

        private void finish(Throwable error) {
            if (!closed.compareAndSet(false, true)) return;
            connections.remove(this);
            synchronized (this) { queue.clear(); offline.clear(); notifyAll(); }
            Thread task = worker;
            if (task != null && task != Thread.currentThread()) task.interrupt();
            if (error != null) sendFailureCounter.increment();
            Thread.ofVirtual().name("sse-close").start(() -> {
                try { emitter.complete(); } finally { taskFinished(); }
            });
        }
    }
}
