package com.robot.fleet.domain.command.service;

import com.robot.fleet.domain.command.repository.CommandOutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.kafka.core.KafkaTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="command.durable-enabled",havingValue="true")
public class CommandOutboxPublisher {
    private final CommandOutboxRepository repository;
    private final KafkaTemplate<String,String> kafka;
    private final MeterRegistry metrics;
    private final String fleet;
    public CommandOutboxPublisher(CommandOutboxRepository repository,KafkaTemplate<String,String> kafka,MeterRegistry metrics,@Value("${fleet.id:default}") String fleet) {
        this.repository=repository; this.kafka=kafka; this.metrics=metrics; this.fleet=fleet;
    }
    @Scheduled(fixedDelay=500)
    public void publish() {
        var token=UUID.randomUUID();
        for (var entry:repository.claim(fleet,token)) {
            try {
                if (repository.now().isBefore(entry.expiresAt())) {
                    kafka.send("cmd.robot",entry.robotId(),entry.payload()).get(5,TimeUnit.SECONDS);
                    metrics.counter("command.publish", "result","success").increment();
                }
            } catch (Exception e) {
                metrics.counter("command.publish", "result","failure").increment();
                if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); return; }
            } finally { repository.release(entry.cmdId(),token); }
        }
    }
}
