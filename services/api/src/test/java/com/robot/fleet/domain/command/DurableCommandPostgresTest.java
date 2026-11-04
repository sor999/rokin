package com.robot.fleet.domain.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.command.repository.CommandOutboxRepository;
import com.robot.fleet.domain.command.service.DurableCommandService;
import com.robot.fleet.domain.command.dto.*;
import com.robot.fleet.domain.ack.service.AckQueryService;
import com.robot.fleet.domain.realtime.RealtimeState;
import com.robot.fleet.domain.simulation.service.SimulationService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="PRESENCE_TEST_URL",matches=".+")
class DurableCommandPostgresTest {
    private String schema;
    private JdbcTemplate jdbc;
    private CommandOutboxRepository repo;
    private DurableCommandService service;
    private SimulationService simulations;
    private DriverManagerDataSource source;
    private String url() { return System.getenv("PRESENCE_TEST_URL"); }
    @BeforeEach void setup() {
        schema="outbox_"+UUID.randomUUID().toString().replace("-","");
        var flyway=Flyway.configure().dataSource(url(),"postgres","rokin_test_only").schemas(schema);
        flyway.getPluginRegister().getPlugin(org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
        flyway.load().migrate();
        source=new DriverManagerDataSource(url()+(url().contains("?")?"&":"?")+"currentSchema="+schema,"postgres","rokin_test_only");
        jdbc=new JdbcTemplate(source); repo=new CommandOutboxRepository(jdbc); simulations=mock(SimulationService.class);
        when(simulations.commandContext(anyString())).thenReturn(Map.of("simulation_runtime_id","runtime","simulation_version",1));
        service=newService("default");
    }
    private DurableCommandService newService(String fleet) {
        return new DurableCommandService(new CommandOutboxRepository(new JdbcTemplate(source)),simulations,new RealtimeState(false),
            mock(AckQueryService.class),new ObjectMapper(),new DataSourceTransactionManager(source),fleet,true);
    }
    @AfterEach void cleanup() { if(jdbc!=null) jdbc.execute("DROP SCHEMA "+schema+" CASCADE"); }
    private CommandRequestDto request(String key) {
        var r=new CommandRequestDto(); r.setRobotId("robot_1"); r.setCommand("stop"); r.setData(new StopCommandDataDto()); r.setIdempotencyKey(key); return r;
    }
    @Test void durableBeforeResponseAndRestart() throws Exception {
        String key=UUID.randomUUID().toString(); String id=service.accept(request(key));
        var entry=repo.byId("default",UUID.fromString(id)).orElseThrow();
        var json=new ObjectMapper().readTree(entry.payload());
        assertThat(json.get("expires_at").asText()).isNotBlank(); assertThat(json.get("command_sequence").asLong()).isEqualTo(1);
        assertThat(newService("default").accept(request(key))).isEqualTo(id);
        assertThat(newService("other").find(UUID.fromString(id))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM command_outbox",Integer.class)).isEqualTo(1);
    }
    @Test void conflictingKeyRejectedAndReadinessNotRequiredForReplay() {
        String key=UUID.randomUUID().toString(); String id=service.accept(request(key));
        when(simulations.commandContext(anyString())).thenThrow(new IllegalStateException("offline"));
        assertThat(service.accept(request(key))).isEqualTo(id);
        var changed=request(key); changed.setRobotId("robot_2");
        assertThatThrownBy(()->service.accept(changed)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->service.accept(request(UUID.randomUUID().toString()))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM command_outbox",Integer.class)).isEqualTo(1);
    }
    @Test void competingApisCreateOnlyOneCommand() throws Exception {
        String key=UUID.randomUUID().toString(); var gate=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new ArrayList<Future<String>>();
            for(int i=0;i<8;i++) tasks.add(executor.submit(()->{gate.await();return newService("default").accept(request(key));}));
            gate.countDown(); var ids=new HashSet<String>(); for(var task:tasks) ids.add(task.get(10,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM command_outbox",Integer.class)).isEqualTo(1);
    }
    @Test void leasesFenceStaleCompletionAndReceiptStopsRetries() {
        UUID id=UUID.fromString(service.accept(request(UUID.randomUUID().toString())));
        UUID old=UUID.randomUUID(), current=UUID.randomUUID();
        assertThat(repo.claim("default",old)).hasSize(1); assertThat(repo.claim("default",current)).isEmpty();
        jdbc.update("UPDATE command_outbox SET lease_until=clock_timestamp()-interval '1 second'");
        assertThat(repo.claim("default",current)).hasSize(1); repo.release(id,old);
        assertThat(jdbc.queryForObject("SELECT lease_token FROM command_outbox",UUID.class)).isEqualTo(current);
        repo.received("other",id,"robot_1",false); assertThat(repo.byId("default",id).orElseThrow().receivedAt()).isNull();
        repo.received("default",id,"robot_1",false); repo.release(id,current);
        assertThat(repo.claim("default",UUID.randomUUID())).isEmpty();
    }
    @Test void expiryDoesNotFabricateRobotAck() {
        UUID id=UUID.fromString(service.accept(request(UUID.randomUUID().toString())));
        jdbc.update("UPDATE command_outbox SET expires_at=clock_timestamp()-interval '1 second'");
        assertThat(service.find(id).orElseThrow().deliveryStatus()).isEqualTo("EXPIRED");
        jdbc.update("UPDATE command_outbox SET attempts=1");
        assertThat(service.find(id).orElseThrow().deliveryStatus()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(service.find(id).orElseThrow().ack()).isNull();
        assertThat(repo.claim("default",UUID.randomUUID())).isEmpty();
    }
}
