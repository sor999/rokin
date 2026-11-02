package com.robot.fleet.domain.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("realtime")
@RequiredArgsConstructor
public class RealtimeHealth implements HealthIndicator {
    private final RealtimeState state;
    public Health health() {
        return (state.available() ? Health.up() : Health.down()).withDetail("mode", state.enabled() ? "relay" : "local").build();
    }
}
