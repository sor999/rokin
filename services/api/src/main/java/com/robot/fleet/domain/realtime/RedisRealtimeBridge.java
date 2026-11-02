package com.robot.fleet.domain.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.ack.dto.AckDto;
import com.robot.fleet.domain.telemetry.dto.TelemetryEvent;
import com.robot.fleet.domain.telemetry.service.TelemetryStreamService;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.*;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.*;

@Slf4j
@Component
@ConditionalOnProperty(name = "fleet.realtime.enabled", havingValue = "true")
public class RedisRealtimeBridge implements RealtimePublisher, SmartLifecycle {
    private record Connections(long generation, StatefulRedisConnection<String, String> publisher,
                               StatefulRedisPubSubConnection<String, String> subscriber) {
        void close() { try { subscriber.close(); } finally { publisher.close(); } }
    }
    private final RedisClient client;
    private final String channel;
    private final String probeChannel;
    private final String probeToken = java.util.UUID.randomUUID().toString();
    private volatile long lastProbe;
    private final String fleetId;
    private final ObjectMapper mapper;
    private final RealtimeReceiver receiver;
    private final RealtimeState state;
    private final TelemetryStreamService stream;
    private final RealtimeSessions sessions;
    private final MeterRegistry metrics;
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().daemon().name("realtime-maintenance").unstarted(r));
    private final ThreadPoolExecutor delivery = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1024), r -> Thread.ofPlatform().daemon().name("realtime-receiver").unstarted(r));
    private volatile boolean running;
    private volatile Connections connections;

    public RedisRealtimeBridge(@Value("${fleet.realtime.host:localhost}") String host,
                               @Value("${fleet.realtime.port:6379}") int port,
                               @Value("${fleet.realtime.password:}") String password,
                               @Value("${fleet.id:default}") String fleetId,
                               ObjectMapper mapper, RealtimeReceiver receiver, RealtimeState state,
                               TelemetryStreamService stream, RealtimeSessions sessions, MeterRegistry metrics) {
        if (!fleetId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid fleet");
        RedisURI.Builder uri = RedisURI.builder().withHost(host).withPort(port).withTimeout(Duration.ofSeconds(1));
        if (!password.isEmpty()) uri.withPassword(password.toCharArray());
        client = RedisClient.create(uri.build());
        client.setOptions(ClientOptions.builder().autoReconnect(false).build());
        channel = "rokin.realtime." + fleetId;
        probeChannel = channel + ".probe." + probeToken;
        this.fleetId = fleetId; this.mapper = mapper; this.receiver = receiver; this.state = state;
        this.stream = stream; this.sessions = sessions; this.metrics = metrics;
        metrics.gauge("fleet.realtime.available", state, value -> value.available() ? 1 : 0);
        metrics.gauge("fleet.realtime.pending", delivery, pool -> pool.getQueue().size());
    }

    @Override public void start() {
        running = true;
        maintenance.scheduleWithFixedDelay(this::connect, 0, 2, TimeUnit.SECONDS);
    }

    private void connect() {
        if (!running) return;
        Connections existing = connections;
        if (existing != null) {
            try {
                if (System.nanoTime() - lastProbe > TimeUnit.SECONDS.toNanos(6)) throw new IllegalStateException("Subscriber heartbeat expired");
                existing.publisher().sync().publish(probeChannel, probeToken);
            } catch (Exception error) { invalidate(existing.generation()); }
            return;
        }
        long generation = state.generation();
        StatefulRedisConnection<String, String> publisher = null;
        StatefulRedisPubSubConnection<String, String> subscriber = null;
        try {
            publisher = client.connect();
            subscriber = client.connectPubSub();
            RedisConnectionStateListener listener = new RedisConnectionStateListener() {
                @Override public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) { invalidate(generation); }
            };
            publisher.addListener(listener); subscriber.addListener(listener);
            CompletableFuture<Void> subscribed = new CompletableFuture<>();
            subscriber.addListener(new RedisPubSubAdapter<>() {
                @Override public void message(String topic, String body) {
                    if (probeChannel.equals(topic)) {
                        if (probeToken.equals(body) && generation == state.generation()) {
                            lastProbe = System.nanoTime();
                            subscribed.complete(null);
                        }
                        return;
                    }
                    if (body.length() > 65536) {
                        metrics.counter("fleet.realtime.invalid").increment();
                        log.warn("Oversized realtime envelope rejected: {} characters", body.length());
                        return;
                    }
                    try {
                        delivery.execute(() -> {
                            try { receiver.receive(body, generation); }
                            catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException error) {
                                metrics.counter("fleet.realtime.invalid").increment();
                                log.warn("Invalid realtime envelope rejected: {}", error.getClass().getSimpleName());
                            } catch (Exception error) { invalidate(generation); }
                        });
                    } catch (RejectedExecutionException full) { invalidate(generation); }
                }
            });
            subscriber.async().subscribe(channel, probeChannel).get(2, TimeUnit.SECONDS);
            publisher.sync().publish(probeChannel, probeToken);
            subscribed.get(2, TimeUnit.SECONDS);
            connections = new Connections(generation, publisher, subscriber);
            if (!running || !state.activate(generation)) {
                connections = null; subscriber.close(); publisher.close(); return;
            }
            log.info("Realtime subscription ready");
        } catch (Exception error) {
            if (subscriber != null) subscriber.close();
            if (publisher != null) publisher.close();
            log.warn("Realtime connection retry: {}", error.getClass().getSimpleName());
        }
    }

    private void invalidate(long expected) {
        if (state.invalidate(expected) < 0) return;
        metrics.counter("fleet.realtime.resets").increment();
        try {
            maintenance.execute(() -> {
                Connections old = connections;
                connections = null;
                try {
                    if (old != null) old.close();
                } catch (Exception error) {
                    log.warn("Realtime connection close failed: {}", error.getClass().getSimpleName());
                } finally {
                    delivery.getQueue().clear();
                    state.cleanup(() -> { stream.reset(); sessions.reset(); });
                }
            });
        } catch (RejectedExecutionException stopping) { }
    }

    private void publish(String kind, String id, Object payload) {
        state.requireAvailable();
        Connections current = connections;
        if (current == null) throw new IllegalStateException("Realtime connection unavailable");
        try {
            String data = mapper.writeValueAsString(new RealtimeReceiver.Envelope(1, fleetId, kind, id, mapper.valueToTree(payload)));
            long subscribers = current.publisher().sync().publish(channel, data);
            if (subscribers < 1) throw new IllegalStateException("No realtime subscriber");
        } catch (Exception error) {
            invalidate(current.generation());
            throw new IllegalStateException("Realtime publish failed");
        }
    }

    @Override public void telemetry(String eventId, TelemetryEvent event) { publish("telemetry", eventId, event); }
    @Override public void ack(AckDto event) { publish("ack", event.cmdId() + ":" + event.status(), event); }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return 0; }
    @Override public void stop() {
        running = false;
        invalidate(state.generation());
        maintenance.shutdown();
        try { maintenance.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        delivery.shutdownNow();
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
}
