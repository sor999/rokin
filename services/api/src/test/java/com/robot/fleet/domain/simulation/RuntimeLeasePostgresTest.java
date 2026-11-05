package com.robot.fleet.domain.simulation;

import com.robot.fleet.domain.simulation.dto.*;
import com.robot.fleet.domain.simulation.repository.SimulationRepository;
import com.robot.fleet.domain.simulation.service.SimulationService;
import com.robot.fleet.domain.telemetry.service.OfflineDetectorService;
import com.robot.fleet.domain.presence.repository.PresenceRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="PRESENCE_TEST_URL",matches=".+")
class RuntimeLeasePostgresTest {
    private String schema;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private SimulationRepository repository;
    private SimulationService apiA,apiB;
    private String url(){return System.getenv("PRESENCE_TEST_URL");}
    private String user(){return System.getenv().getOrDefault("PRESENCE_TEST_USER","postgres");}
    private String password(){return System.getenv().getOrDefault("PRESENCE_TEST_PASSWORD","rokin_test_only");}

    @BeforeEach void prepare() {
        schema="lease_test_"+UUID.randomUUID().toString().replace("-","");
        var config=Flyway.configure().dataSource(url(),user(),password()).schemas(schema);
        config.getPluginRegister().getPlugin(org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
        config.load().migrate();
        var source=new DriverManagerDataSource(url()+(url().contains("?")?"&":"?")+"currentSchema="+schema+"&ApplicationName="+schema,user(),password());
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(5);
        repository=new SimulationRepository(jdbc);
        var offline=mock(OfflineDetectorService.class);
        apiA=new SimulationService(repository,offline,"default",20,true,12000);
        apiB=new SimulationService(new SimulationRepository(new JdbcTemplate(source)),offline,"default",20,true,12000);
        tx.executeWithoutResult(status->apiA.create(new CreateSimulationRequest("leased_robot",0.0,0.0,1.0)));
    }

    @AfterEach void cleanup() throws Exception {
        try(var connection=DriverManager.getConnection(url(),user(),password());var sql=connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        }
    }

    private RuntimeLeaseDto claim(SimulationService api,String runtime){return tx.execute(status->api.lease(runtime));}
    private void report(String runtime,long epoch){tx.executeWithoutResult(status->apiA.report(new SimulationReport("leased_robot",1,"RUNNING",null,runtime,epoch)));}

    @Test @DisplayName("실제 Fleet 잠금으로 두 API의 동시 획득을 직렬화한다")
    void acquire_SerializesTwoApis() throws Exception {
        // given
        String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->tx.execute(status->{
                repository.lockFleet("default");locked.countDown();
                try {if(!release.await(4,TimeUnit.SECONDS))throw new AssertionError("release timeout");}
                catch(InterruptedException error){throw new RuntimeException(error);}
                return apiA.lease(a);
            }));
            assertThat(locked.await(2,TimeUnit.SECONDS)).isTrue();
            var second=executor.submit(()->claim(apiB,b));
            // when: 실제 DB lock wait가 보인 뒤에만 첫 트랜잭션을 진행한다.
            boolean waiting=false;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            try {
                while(System.nanoTime()<deadline && !waiting) {
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock')",Boolean.class,schema));
                    if(!waiting)Thread.sleep(10);
                }
                assertThat(waiting).isTrue();
            } finally {release.countDown();}
            // then
            assertThat(first.get(4,TimeUnit.SECONDS).granted()).isTrue();
            assertThat(second.get(4,TimeUnit.SECONDS).granted()).isFalse();
            var renew=claim(apiB,a);
            assertThat(renew.granted()).isTrue();
            assertThat(renew.epoch()).isEqualTo(first.get().epoch());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM simulation_runtime_session",Integer.class)).isEqualTo(1);
        }
    }

    @Test @DisplayName("만료 후 명령과 관측 및 보고를 차단하고 새 epoch로만 복구한다")
    void expiry_FencesOldRuntimeAndNeverResumesIt() {
        // given
        String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
        var oldMode=new SimulationService(repository,mock(OfflineDetectorService.class),"default",20);
        tx.executeWithoutResult(status->oldMode.registerRuntime(UUID.randomUUID().toString()));
        var first=claim(apiA,a);report(a,first.epoch());
        var presence=new PresenceRepository(jdbc);
        assertThat(presence.observe("default","leased_robot",a,OffsetDateTime.now(),30)).isTrue();
        assertThat(tx.<java.util.Map<String,Object>>execute(status->apiA.commandContext("leased_robot"))).containsEntry("simulation_epoch",first.epoch());
        var reported=jdbc.queryForObject("SELECT reported_at FROM simulation_robot WHERE robot_id='leased_robot'",OffsetDateTime.class);
        // when: 로컬 기한보다 늦은 서버 임대의 마지막 구간에도 새 명령은 접수하지 않는다.
        jdbc.update("UPDATE simulation_runtime_session SET lease_expires_at=clock_timestamp()+INTERVAL '4 second' WHERE runtime_id=?",a);
        assertThatThrownBy(()->tx.execute(status->apiA.commandContext("leased_robot"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        jdbc.update("UPDATE simulation_runtime_session SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE runtime_id=?",a);
        // then
        assertThat(claim(apiB,a).retired()).isTrue();
        assertThat(repository.currentRuntime("default")).isEmpty();
        assertThatThrownBy(()->tx.execute(status->apiA.commandContext("leased_robot"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(presence.online("default","leased_robot",a,30)).isFalse();
        assertThat(presence.observe("default","leased_robot",a,OffsetDateTime.now(),30)).isFalse();
        assertThat(presence.expired("default",30)).extracting(PresenceRepository.Expired::robotId).contains("leased_robot");
        report(a,first.epoch());
        assertThat(jdbc.queryForObject("SELECT reported_at FROM simulation_robot WHERE robot_id='leased_robot'",OffsetDateTime.class)).isEqualTo(reported);
        var next=claim(apiB,b);
        assertThat(next.granted()).isTrue();assertThat(next.epoch()).isGreaterThan(first.epoch());
        assertThatThrownBy(()->tx.execute(status->apiA.commandContext("leased_robot"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        report(b,first.epoch());
        assertThat(jdbc.queryForObject("SELECT runtime_id FROM simulation_robot WHERE robot_id='leased_robot'",String.class)).isEqualTo(a);
        report(b,next.epoch());
        assertThat(tx.<java.util.Map<String,Object>>execute(status->apiA.commandContext("leased_robot"))).containsEntry("simulation_runtime_id",b);
        var legacy=new SimulationService(repository,mock(OfflineDetectorService.class),"default",20);
        assertThatThrownBy(()->tx.executeWithoutResult(status->legacy.registerRuntime(UUID.randomUUID().toString()))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
