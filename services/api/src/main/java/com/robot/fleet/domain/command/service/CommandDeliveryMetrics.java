package com.robot.fleet.domain.command.service;

import com.robot.fleet.domain.command.repository.CommandOutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class CommandDeliveryMetrics {
    private final AtomicReference<Map<String,Number>> journal = new AtomicReference<>(Map.of());
    public CommandDeliveryMetrics(CommandOutboxRepository repository, MeterRegistry registry,
                                  @Value("${fleet.id:default}") String fleet) {
        for (String state: java.util.List.of("pending","expired","unknown")) {
            io.micrometer.core.instrument.Gauge.builder("command.delivery",repository,r -> r.count(fleet,state))
                .tag("state",state).register(registry);
        }
        io.micrometer.core.instrument.Gauge.builder("command.oldest.pending.seconds",repository,r -> r.oldest(fleet)).register(registry);
        for (String key: java.util.List.of("unconfirmedAcks","oldestAckAgeSeconds","diskBytes","storageFailures","retransmissions")) {
            io.micrometer.core.instrument.Gauge.builder("command.journal",journal,j -> j.get().getOrDefault(key,0).doubleValue())
                .tag("kind",key).register(registry);
        }
    }
    public void report(Map<String,Number> values) {
        if (values==null || values.size()>10 || values.values().stream().anyMatch(v -> v==null || !Double.isFinite(v.doubleValue()) || v.doubleValue()<0))
            throw new IllegalArgumentException("잘못된 저장소 지표");
        journal.set(Map.copyOf(values));
    }
}
