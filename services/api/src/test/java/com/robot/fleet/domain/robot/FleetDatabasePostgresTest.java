package com.robot.fleet.domain.robot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.presence.repository.PresenceRepository;
import com.robot.fleet.domain.presence.service.PresenceService;
import com.robot.fleet.domain.robot.repository.RobotSnapshotRepository;
import com.robot.fleet.domain.simulation.dto.*;
import com.robot.fleet.domain.simulation.repository.SimulationRepository;
import com.robot.fleet.domain.simulation.service.SimulationService;
import com.robot.fleet.domain.telemetry.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@Timeout(30)
@EnabledIfEnvironmentVariable(named = "PRESENCE_TEST_URL", matches = ".+")
class FleetDatabasePostgresTest {
    private String schema;
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private SimulationService service;
    private RobotSnapshotRepository snapshots;
    private TelemetryStreamService stream;
    private final String runtime = UUID.randomUUID().toString();
    private String url() { return System.getenv("PRESENCE_TEST_URL"); }
    private String user() { return System.getenv().getOrDefault("PRESENCE_TEST_USER", "postgres"); }
    private String password() { return System.getenv().getOrDefault("PRESENCE_TEST_PASSWORD", "rokin_test_only"); }

    private Flyway migration(String name, String target) {
        var config = Flyway.configure().dataSource(url(),user(),password()).schemas(name);
        config.getPluginRegister().getPlugin(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
        if (target != null) config.target(target);
        return config.load();
    }
    @BeforeEach void prepare() {
        schema = "fleet_db_" + UUID.randomUUID().toString().replace("-", "");
        migration(schema, null).migrate();
        source = new DriverManagerDataSource(url() + (url().contains("?") ? "&" : "?")
                + "currentSchema=" + schema + "&ApplicationName=" + schema + "&socketTimeout=5", user(), password());
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        stream = new TelemetryStreamService(new ObjectMapper(), new SimpleMeterRegistry());
        service = simulation("default", 3);
        snapshots = new RobotSnapshotRepository(jdbc, "default", 30);
        tx.executeWithoutResult(status -> service.registerRuntime(runtime));
    }
    @AfterEach void cleanup() throws Exception {
        if (stream != null) stream.close();
        try (var c=DriverManager.getConnection(url(),user(),password()); var sql=c.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
    private SimulationService simulation(String fleet, int maximum) {
        var presence = new PresenceService(new PresenceRepository(jdbc), fleet, 30);
        return new SimulationService(new SimulationRepository(jdbc), new OfflineDetectorService(presence,stream),fleet,maximum);
    }
    private void create(SimulationService target, String robot) {
        tx.executeWithoutResult(status -> target.create(new CreateSimulationRequest(robot,0.0,0.0,1.0)));
    }
    private void report(String robot, long version, String state, String instance) {
        tx.executeWithoutResult(status -> service.report(new SimulationReport(robot,version,state,null,instance)));
    }
    private void pose(JdbcTemplate db, String robot, String instance, UUID event, OffsetDateTime at, double x) {
        db.update("INSERT INTO telemetry_pose(event_id,timestamp,robot_id,x,y,simulation_runtime_id) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                event,at,robot,x,2,instance);
    }
    private OffsetDateTime now() { return jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class); }
    private JdbcTemplate connectionJdbc(Connection c) { return new JdbcTemplate(new SingleConnectionDataSource(c,true)); }
    private void waitForLocks(int count) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime()<deadline) {
            var blocked=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",Integer.class,schema);
            if (blocked >= count) return;
            Thread.sleep(20);
        }
        fail("Expected actual PostgreSQL lock contention");
    }

    @Test @DisplayName("최신 투영은 중복과 시간 역행 및 같은 timestamp의 순서에 수렴한다")
    void projection_PreservesHistoryAndLatestOrder() {
        // given
        var at=now();
        var first=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var next=UUID.fromString("00000000-0000-0000-0000-000000000002");
        // when
        pose(jdbc,"static",null,first,at,1);
        pose(jdbc,"static",null,next,at,2);
        pose(jdbc,"static",null,first,at.plusSeconds(1),99);
        pose(jdbc,"static",null,UUID.randomUUID(),at.minusSeconds(1),99);
        // then
        assertThat(snapshots.find("static").orElseThrow().pose().x()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM telemetry_pose",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM robot_latest",Integer.class)).isEqualTo(1);
        // when, then
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            pose(jdbc,"static",null,UUID.randomUUID(),at.plusSeconds(2),77);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(snapshots.find("static").orElseThrow().pose().x()).isEqualTo(2);
    }

    @Test @DisplayName("runtime 교체와 삭제 및 다른 Fleet의 데이터는 최신 화면에 섞이지 않는다")
    void snapshot_FencesRuntimeDeletionAndFleet() {
        // given
        create(service,"managed"); report("managed",1,"RUNNING",runtime);
        pose(jdbc,"managed",runtime,UUID.randomUUID(),now(),1);
        assertThat(snapshots.find("managed").orElseThrow().pose().x()).isEqualTo(1);
        String next=UUID.randomUUID().toString();
        // when
        tx.executeWithoutResult(status -> service.registerRuntime(next));
        pose(jdbc,"managed",runtime,UUID.randomUUID(),now().plusSeconds(1),99);
        // then
        assertThat(snapshots.find("managed").orElseThrow().runtimeId()).isEqualTo(next);
        assertThat(snapshots.find("managed").orElseThrow().pose()).isNull();
        pose(jdbc,"managed",next,UUID.randomUUID(),now(),2);
        assertThat(snapshots.find("managed").orElseThrow().pose().x()).isEqualTo(2);
        tx.executeWithoutResult(status -> service.delete("managed"));
        assertThat(snapshots.find("managed")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM robot_latest WHERE robot_id='managed'",Integer.class)).isEqualTo(2);
        create(simulation("other",3),"other_robot");
        pose(jdbc,"other_robot",null,UUID.randomUUID(),now(),9);
        assertThat(snapshots.find("other_robot")).isEmpty();
    }

    @Test @DisplayName("목록은 로봇 수와 무관하게 한 쿼리이며 cursor 페이지에 중복이 없다")
    void snapshot_ConstantQueryCountAndCursor() {
        // given
        var calls=new AtomicInteger();
        DataSource counted=new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var actual=source.getConnection();
                return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if (method.getName().equals("prepareStatement")) calls.incrementAndGet();
                    try { return method.invoke(actual,args); }
                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
            }
            @Override public Connection getConnection(String u,String p) throws SQLException { return getConnection(); }
        };
        var reads=new RobotSnapshotRepository(new JdbcTemplate(counted),"default",30);
        pose(jdbc,"static_00",null,UUID.randomUUID(),now(),0);
        // when, then
        assertThat(reads.list("",null)).hasSize(1);
        assertThat(calls.getAndSet(0)).isEqualTo(1);
        for(int i=1;i<20;i++) pose(jdbc,String.format("static_%02d",i),null,UUID.randomUUID(),now(),i);
        assertThat(reads.list("",null)).hasSize(20);
        assertThat(calls.getAndSet(0)).isEqualTo(1);
        var first=reads.list("",7);
        var second=reads.list(first.getLast().robotId(),7);
        assertThat(first).hasSize(7); assertThat(second).hasSize(7);
        assertThat(first.stream().map(r->r.robotId()).toList()).doesNotContainAnyElementsOf(second.stream().map(r->r.robotId()).toList());
        assertThat(reads.find("static_19").orElseThrow().pose().x()).isEqualTo(19);
    }

    @Test @DisplayName("같은 Fleet의 생성 경쟁은 실제 락 대기 후 상한을 넘지 않는다")
    void create_ConcurrentCapacityIsBounded() throws Exception {
        // given
        create(service,"first"); create(service,"second");
        try(var holder=source.getConnection(); var executor=Executors.newFixedThreadPool(8)) {
            holder.setAutoCommit(false);
            new SimulationRepository(connectionJdbc(holder)).lockFleet("default");
            var tasks=new ArrayList<Future<Boolean>>();
            // when
            for(int i=0;i<12;i++) {
                String id="candidate_"+i;
                tasks.add(executor.submit(()->{try{create(service,id);return true;}catch(ResponseStatusException e){assertThat(e.getStatusCode().value()).isEqualTo(409);return false;}}));
            }
            try { waitForLocks(2); } finally { holder.commit(); }
            int accepted=0;
            for(var task:tasks) if(task.get(10,TimeUnit.SECONDS)) accepted++;
            // then
            assertThat(accepted).isEqualTo(1);
            assertThat(new SimulationRepository(jdbc).countActive("default")).isEqualTo(3);
        }
    }

    @Test @DisplayName("다른 Fleet의 등록은 독립적으로 진행하고 전역 ID 중복은 409로 거부한다")
    void create_IndependentFleetsAndGlobalUniqueId() throws Exception {
        // given
        var other=simulation("other",3);
        tx.executeWithoutResult(status -> new SimulationRepository(jdbc).lockFleet("other"));
        try(var holder=source.getConnection();var executor=Executors.newFixedThreadPool(2)) {
            holder.setAutoCommit(false);
            new SimulationRepository(connectionJdbc(holder)).lockFleet("default");
            // when, then
            executor.submit(()->create(other,"other_robot")).get(2,TimeUnit.SECONDS);
            holder.commit();
            var gate=new CountDownLatch(1);
            var one=executor.submit(()->{gate.await();try{create(service,"global_id");return true;}catch(ResponseStatusException e){return false;}});
            var two=executor.submit(()->{gate.await();try{create(other,"global_id");return true;}catch(ResponseStatusException e){return false;}});
            gate.countDown();
            assertThat(List.of(one.get(5,TimeUnit.SECONDS),two.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM simulation_robot WHERE robot_id='global_id'",Integer.class)).isEqualTo(1);
        }
    }

    @Test @DisplayName("한 로봇의 보고 대기가 다른 로봇을 막지 않고 runtime 교체는 보고 종료를 기다린다")
    void report_ParallelRobotsAndRuntimeFence() throws Exception {
        // given
        create(service,"first");create(service,"second");
        try(var holder=source.getConnection();var executor=Executors.newFixedThreadPool(2)) {
            holder.setAutoCommit(false);
            connectionJdbc(holder).queryForObject("SELECT robot_id FROM simulation_robot WHERE robot_id='first' FOR UPDATE",String.class);
            var blocked=executor.submit(()->report("first",1,"RUNNING",runtime));
            try {
                waitForLocks(1);
                // when, then
                executor.submit(()->report("second",1,"RUNNING",runtime)).get(2,TimeUnit.SECONDS);
                assertThat(new SimulationRepository(jdbc).find("default","second").orElseThrow().state()).isEqualTo("RUNNING");
                String next=UUID.randomUUID().toString();
                var replacement=executor.submit(()->tx.executeWithoutResult(status->service.registerRuntime(next)));
                waitForLocks(2);
                holder.commit();
                blocked.get(5,TimeUnit.SECONDS);replacement.get(5,TimeUnit.SECONDS);
                report("first",1,"RUNNING",next);
                report("first",1,"FAILED",runtime);
                assertThat(new SimulationRepository(jdbc).find("default","first").orElseThrow().runtimeId()).isEqualTo(next);
                assertThat(new SimulationRepository(jdbc).find("default","first").orElseThrow().state()).isEqualTo("RUNNING");
            } finally { holder.rollback(); }
        }
    }

    @Test @DisplayName("삭제와 경합한 이전 버전 보고는 삭제 의도와 버전을 되돌리지 않는다")
    void report_CompetingDeletePreservesTombstone() throws Exception {
        // given
        create(service,"first");
        try(var holder=source.getConnection();var executor=Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            var held=connectionJdbc(holder);
            held.queryForObject("SELECT robot_id FROM simulation_robot WHERE robot_id='first' FOR UPDATE",String.class);
            var pending=executor.submit(()->report("first",1,"RUNNING",runtime));
            try {
                waitForLocks(1);
                // when
                new SimulationRepository(held).delete("first");
                holder.commit();pending.get(5,TimeUnit.SECONDS);
                // then
                var robot=new SimulationRepository(jdbc).find("default","first").orElseThrow();
                assertThat(robot.version()).isEqualTo(2);assertThat(robot.state()).isEqualTo("DELETING");
            } finally { holder.rollback(); }
        }
    }
    @Test @DisplayName("기존 이력을 보존하며 최신 투영을 채우고 구버전 Worker의 적재를 계속 반영한다")
    void migrate_BackfillsAndPreservesLegacyWorkerWrites() throws Exception {
        // given
        String legacy=schema+"_legacy";
        try {
            migration(legacy,"9").migrate();
            var legacySource=new DriverManagerDataSource(url()+(url().contains("?")?"&":"?")+"currentSchema="+legacy,user(),password());
            var legacyJdbc=new JdbcTemplate(legacySource);
            var at=now();
            pose(legacyJdbc,"legacy",null,UUID.randomUUID(),at.minusSeconds(1),1);
            pose(legacyJdbc,"legacy",null,UUID.randomUUID(),at,2);
            legacyJdbc.update("INSERT INTO telemetry_battery(event_id,timestamp,robot_id,level) VALUES (?,?,?,?)",UUID.randomUUID(),at,"legacy",80);
            legacyJdbc.update("INSERT INTO telemetry_status(event_id,timestamp,robot_id,state) VALUES (?,?,?,?)",UUID.randomUUID(),at,"legacy","moving");
            // when
            migration(legacy,null).migrate();
            var legacyReads=new RobotSnapshotRepository(legacyJdbc,"default",30);
            // then
            var robot=legacyReads.find("legacy").orElseThrow();
            assertThat(robot.pose().x()).isEqualTo(2);
            assertThat(robot.battery().level()).isEqualTo(80);
            assertThat(robot.state()).isEqualTo("moving");
            assertThat(legacyJdbc.queryForObject("SELECT count(*) FROM telemetry_pose",Integer.class)).isEqualTo(2);
            // when: existing INSERT contracts are unchanged.
            legacyJdbc.update("INSERT INTO telemetry_battery(event_id,timestamp,robot_id,level) VALUES (?,?,?,?)",UUID.randomUUID(),at.plusSeconds(1),"legacy",70);
            legacyJdbc.update("INSERT INTO telemetry_status(event_id,timestamp,robot_id,state) VALUES (?,?,?,?)",UUID.randomUUID(),at.plusSeconds(1),"legacy","idle");
            // then
            assertThat(legacyReads.find("legacy").orElseThrow().battery().level()).isEqualTo(70);
            assertThat(legacyReads.find("legacy").orElseThrow().state()).isEqualTo("idle");
        } finally {
            try(var c=DriverManager.getConnection(url(),user(),password());var sql=c.createStatement()) { sql.execute("DROP SCHEMA IF EXISTS "+legacy+" CASCADE"); }
        }
    }

}
