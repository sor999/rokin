package com.robot.fleet.domain.command.service;

import com.robot.fleet.domain.command.repository.CommandOutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.kafka.core.KafkaTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="command.durable-enabled",havingValue="true")
public class CommandOutboxPublisher {
    private final CommandOutboxRepository repository;
    private final KafkaTemplate<String,String> kafka;
    private final MeterRegistry metrics;
    private final String fleet;
    private final Semaphore capacity=new Semaphore(10);
    private final ExecutorService workers=Executors.newFixedThreadPool(10,
        Thread.ofPlatform().name("command-outbox-",0).daemon(true).factory());
    public CommandOutboxPublisher(CommandOutboxRepository repository,@Qualifier("outboxKafkaTemplate") KafkaTemplate<String,String> kafka,
                                 MeterRegistry metrics,@Value("${fleet.id:default}") String fleet) {
        this.repository=repository; this.kafka=kafka; this.metrics=metrics; this.fleet=fleet;
    }
    @Scheduled(fixedDelay=100)
    public synchronized void publish() {
        int slots=capacity.availablePermits();
        if(slots==0 || workers.isShutdown()) return;
        UUID token=UUID.randomUUID();
        for(var entry:repository.claim(fleet,token,slots)) {
            capacity.acquireUninterruptibly();
            try { workers.execute(()->send(entry,token)); }
            catch(RejectedExecutionException e) { capacity.release(); repository.release(entry.cmdId(),token); }
        }
    }
    private void send(CommandOutboxRepository.Entry entry,UUID token) {
        try {
            var latest=repository.byId(fleet,entry.cmdId());
            if(latest.isPresent() && latest.get().receivedAt()==null && repository.now().isBefore(entry.expiresAt())) {
                kafka.send("cmd.robot",entry.robotId(),entry.payload()).get(2500,TimeUnit.MILLISECONDS);
                metrics.counter("command.publish", "result","success").increment();
            }
        } catch(Exception e) {
            metrics.counter("command.publish", "result","failure").increment();
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            try { repository.release(entry.cmdId(),token); }
            finally { capacity.release(); }
        }
    }
    @PreDestroy public synchronized void close() {
        workers.shutdown();
        try { if(!workers.awaitTermination(6,TimeUnit.SECONDS)) workers.shutdownNow(); }
        catch(InterruptedException e) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
