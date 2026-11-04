package com.robot.fleet.domain.command;

import com.robot.fleet.domain.command.repository.CommandOutboxRepository;
import com.robot.fleet.domain.command.service.CommandOutboxPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandOutboxPublisherTest {
    @Test void stalledPublishesNeverExceedTenInFlightTasks() throws Exception {
        var repository=mock(CommandOutboxRepository.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        var entries=new java.util.ArrayList<CommandOutboxRepository.Entry>();
        for(int i=0;i<10;i++) entries.add(new CommandOutboxRepository.Entry(UUID.randomUUID(),"robot_"+i,"h","move",OffsetDateTime.now().plusSeconds(10),null,1,false));
        when(repository.claim(eq("default"),any(),eq(10))).thenReturn(entries);
        for(var entry:entries) when(repository.byId("default",entry.cmdId())).thenReturn(Optional.of(entry));
        when(repository.now()).thenAnswer(inv->OffsetDateTime.now());
        var started=new CountDownLatch(10);
        var pending=new CompletableFuture<org.springframework.kafka.support.SendResult<String,String>>();
        when(kafka.send(anyString(),anyString(),anyString())).thenAnswer(inv->{started.countDown();return pending;});
        var publisher=new CommandOutboxPublisher(repository,kafka,new SimpleMeterRegistry(),"default");
        try {
            publisher.publish();assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            publisher.publish();verify(repository,times(1)).claim(eq("default"),any(),anyInt());
        } finally { pending.complete(null);publisher.close(); }
    }
    @Test void delayedMoveDoesNotBlockStopAndPublisherCapacityIsBounded() throws Exception {
        var repository=mock(CommandOutboxRepository.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        var expiry=OffsetDateTime.now().plusSeconds(5);
        var move=new CommandOutboxRepository.Entry(UUID.randomUUID(),"robot_1","h","move",expiry,null,1,false);
        var stop=new CommandOutboxRepository.Entry(UUID.randomUUID(),"robot_1","h","stop",expiry,null,1,false);
        when(repository.claim(eq("default"),any(),eq(10))).thenReturn(List.of(move,stop));
        when(repository.byId(eq("default"),eq(move.cmdId()))).thenReturn(Optional.of(move));
        when(repository.byId(eq("default"),eq(stop.cmdId()))).thenReturn(Optional.of(stop));
        when(repository.now()).thenReturn(OffsetDateTime.now());
        var movePending=new CompletableFuture<org.springframework.kafka.support.SendResult<String,String>>();
        when(kafka.send("cmd.robot","robot_1","move")).thenReturn(movePending);
        var stopSent=new CountDownLatch(1);
        when(kafka.send("cmd.robot","robot_1","stop")).thenAnswer(inv->{stopSent.countDown();return CompletableFuture.completedFuture(null);});
        var publisher=new CommandOutboxPublisher(repository,kafka,new SimpleMeterRegistry(),"default");
        try {
            publisher.publish();
            assertThat(stopSent.await(1,TimeUnit.SECONDS)).isTrue();
            assertThat(movePending.isDone()).isFalse();
        } finally { movePending.complete(null);publisher.close(); }
        verify(repository).release(eq(move.cmdId()),any());verify(repository).release(eq(stop.cmdId()),any());
    }
}
