package com.robot.fleet.domain.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RealtimeState {
    private final boolean enabled;
    private final AtomicBoolean available;
    private final AtomicLong generation = new AtomicLong();

    public RealtimeState(@Value("${fleet.realtime.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        this.available = new AtomicBoolean(!enabled);
    }

    public boolean enabled() { return enabled; }
    public boolean available() { return available.get(); }
    public long generation() { return generation.get(); }

    public void requireAvailable() {
        if (!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "실시간 경로를 복구하고 있습니다.");
    }

    public synchronized boolean activate(long expected) {
        if (generation.get() != expected) return false;
        available.set(true);
        return true;
    }

    public synchronized long invalidate(long expected) {
        if (!generation.compareAndSet(expected, expected + 1)) return -1;
        available.set(false);
        return expected + 1;
    }

    public synchronized void ifCurrent(long expected, Runnable action) {
        if (available() && generation.get() == expected) action.run();
    }

    public synchronized void cleanup(Runnable action) { action.run(); }
    public synchronized <T> T whenAvailable(java.util.function.Supplier<T> action) {
        requireAvailable();
        return action.get();
    }
}
