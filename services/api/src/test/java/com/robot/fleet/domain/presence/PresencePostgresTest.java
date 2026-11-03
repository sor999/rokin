package com.robot.fleet.domain.presence;

import com.robot.fleet.domain.presence.repository.PresenceRepository;
import com.robot.fleet.domain.presence.service.PresenceService;
import com.robot.fleet.domain.simulation.dto.CreateSimulationRequest;
import com.robot.fleet.domain.simulation.dto.SimulationReport;
import com.robot.fleet.domain.simulation.repository.SimulationRepository;
import com.robot.fleet.domain.simulation.service.SimulationService;
import com.robot.fleet.domain.telemetry.dto.PoseTelemetryDataDto;
import com.robot.fleet.domain.telemetry.dto.PoseTelemetryDto;
import com.robot.fleet.domain.telemetry.service.OfflineDetectorService;
import com.robot.fleet.domain.telemetry.service.TelemetryStreamService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PRESENCE_TEST_URL", matches = ".+")
class PresencePostgresTest {
    private String schema;
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private PresenceService first, second;
    private PresenceRepository repository;
    private SimulationService apiA, apiB;
    private TelemetryStreamService stream;
    private final String runtime = UUID.randomUUID().toString();
    private String url() { return System.getenv("PRESENCE_TEST_URL"); }
    private String user() { return System.getenv().getOrDefault("PRESENCE_TEST_USER", "postgres"); }
    private String password() { return System.getenv().getOrDefault("PRESENCE_TEST_PASSWORD", "rokin_test_only"); }

    @BeforeEach
    void prepare() {
        schema = "presence_test_" + UUID.randomUUID().toString().replace("-", "");
        var configuration = Flyway.configure().dataSource(url(), user(), password()).schemas(schema);
        configuration.getPluginRegister().getPlugin(org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension.class)
                .setTransactionalLock(false);
        configuration.load().migrate();
        source = new DriverManagerDataSource(url() + (url().contains("?") ? "&" : "?")
                + "currentSchema=" + schema + "&socketTimeout=2&connectTimeout=2", user(), password());
        jdbc = new JdbcTemplate(source);
        repository = new PresenceRepository(jdbc);
        first = new PresenceService(repository, "default", 30);
        second = new PresenceService(new PresenceRepository(new JdbcTemplate(source)), "default", 30);
        stream = new TelemetryStreamService(new ObjectMapper(), new SimpleMeterRegistry());
        apiA = new SimulationService(new SimulationRepository(jdbc), new OfflineDetectorService(first, stream), "default", 20);
        apiB = new SimulationService(new SimulationRepository(new JdbcTemplate(source)), new OfflineDetectorService(second, stream), "default", 20);
        apiA.create(new CreateSimulationRequest("robot", 0.0, 0.0, 1.0));
        apiA.registerRuntime(runtime);
        apiA.report(new SimulationReport("robot", 1, "RUNNING", null, runtime));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (stream != null) stream.close();
        try (var admin = DriverManager.getConnection(url(), user(), password()); var sql = admin.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private PoseTelemetryDto event(String id, OffsetDateTime time) {
        return new PoseTelemetryDto("pose", "robot", time, id, new PoseTelemetryDataDto(1, 2));
    }
    private OffsetDateTime now() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class);
    }
    private void refused(SimulationService service) {
        assertThatThrownBy(() -> service.commandContext("robot")).isInstanceOf(ResponseStatusException.class);
    }

    @Test @DisplayName("한 API의 관측으로 두 API와 재시작 API가 같은 명령 판정을 얻는다")
    void online_SharedAcrossNodesAndRestart() {
        // given
        refused(apiA); refused(apiB);
        // when
        assertThat(first.observe(event(runtime, now()))).isTrue();
        // then
        assertThat(apiA.commandContext("robot")).isEqualTo(apiB.commandContext("robot"));
        assertThat(second.online("robot", runtime)).isTrue();
        stream.reset();
        var restarted = new PresenceService(new PresenceRepository(new JdbcTemplate(source)), "default", 30);
        assertThat(restarted.online("robot", runtime)).isTrue();
        assertThat(new PresenceService(repository, "other", 30).online("robot", runtime)).isFalse();
    }

    @Test @DisplayName("지연 및 중복 관측은 만료를 연장하거나 시각을 역행시키지 않는다")
    void observe_RejectsReplayAndFutureAndNeverRewinds() {
        // given
        var timestamp = now().minusSeconds(5);
        first.observe(event(runtime, timestamp));
        // when
        first.observe(event(runtime, timestamp.minusSeconds(10)));
        first.observe(event(runtime, timestamp));
        // then
        assertThat(jdbc.queryForObject("SELECT observed_at FROM robot_presence", OffsetDateTime.class)).isEqualTo(timestamp);
        assertThat(first.observe(event(runtime, now().minusSeconds(31)))).isFalse();
        assertThat(first.observe(event(runtime, now().plusSeconds(10)))).isFalse();
        jdbc.update("UPDATE robot_presence SET observed_at = CURRENT_TIMESTAMP - INTERVAL '31' SECOND");
        assertThat(second.online("robot", runtime)).isFalse();
        assertThat(first.observe(event(runtime, now().minusSeconds(31)))).isFalse();
        refused(apiA); refused(apiB);
        assertThat(first.expired()).singleElement().extracting(PresenceRepository.Expired::robotId).isEqualTo("robot");
    }

    @Test @DisplayName("runtime 교체와 삭제 이후 과거 실행의 지연 쓰기가 명령을 허용하지 않는다")
    void online_FencesReplacementDeletionAndHeartbeat() {
        // given
        first.observe(event(runtime, now()));
        String next = UUID.randomUUID().toString();
        // when
        apiA.registerRuntime(next);
        first.observe(event(runtime, now()));
        // then
        assertThat(second.online("robot", runtime)).isFalse();
        assertThat(second.online("robot", next)).isFalse();
        refused(apiA); refused(apiB);
        first.observe(event(next, now()));
        refused(apiA); refused(apiB);
        apiA.report(new SimulationReport("robot", 1, "RUNNING", null, next));
        assertThat(apiB.commandContext("robot")).containsEntry("simulation_runtime_id", next);
        jdbc.update("UPDATE simulation_robot SET reported_at = CURRENT_TIMESTAMP - INTERVAL '11' SECOND");
        assertThat(second.online("robot", next)).isFalse();
        assertThat(first.expired()).hasSize(1);
        apiA.report(new SimulationReport("robot", 1, "RUNNING", null, next));
        apiA.delete("robot");
        first.observe(event(next, now()));
        assertThat(second.online("robot", next)).isFalse();
        refused(apiA); refused(apiB);
    }

    @Test @DisplayName("동시 관측에서도 한 행과 최대 timestamp를 보존한다")
    void observe_ConcurrentDuplicateUpdatesPreserveMaximum() throws Exception {
        // given
        var start = new CountDownLatch(1);
        var timestamp = now().minusSeconds(5);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<Future<Boolean>>();
            for (int i = 0; i < 16; i++) {
                var time = timestamp.minusSeconds(i);
                tasks.add(executor.submit(() -> { start.await(); return first.observe(event(runtime, time)); }));
            }
            // when
            start.countDown();
            for (var task : tasks) assertThat(task.get(10, TimeUnit.SECONDS)).isTrue();
        }
        // then
        assertThat(jdbc.queryForObject("SELECT count(*) FROM robot_presence", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT observed_at FROM robot_presence", OffsetDateTime.class)).isEqualTo(timestamp);
    }

    @Test @DisplayName("정적 로봇은 runtime 없는 새 관측이 있어야 명령을 허용한다")
    void online_StaticRequiresFreshObservation() {
        // given
        assertThat(second.online("static", null)).isFalse();
        assertThatThrownBy(() -> apiB.commandContext("static")).isInstanceOf(ResponseStatusException.class);
        // when
        first.observe(new PoseTelemetryDto("pose", "static", now(), null, new PoseTelemetryDataDto(0, 0)));
        // then
        assertThat(second.online("static", null)).isTrue();
        assertThat(apiB.commandContext("static")).isEmpty();
        assertThat(second.online("static", runtime)).isFalse();
    }

    @Test @DisplayName("만료 관측 정리는 현재 관측과 등록을 보존한다")
    void prune_PreservesRecentAndRegistry() {
        // given
        first.observe(event(runtime, now()));
        jdbc.update("INSERT INTO robot_presence VALUES ('default', 'old', '', CURRENT_TIMESTAMP - INTERVAL '2' DAY, CURRENT_TIMESTAMP - INTERVAL '2' DAY)");
        // when
        repository.prune();
        // then
        assertThat(jdbc.queryForObject("SELECT count(*) FROM robot_presence", Integer.class)).isEqualTo(1);
        assertThat(second.online("robot", runtime)).isTrue();
        assertThat(apiB.list()).hasSize(1);
    }
    @Test @DisplayName("공유 저장소 연결 실패 시 Kafka에 명령을 발행하지 않는다")
    void command_RejectsUnavailablePresenceBeforePublish() {
        // given
        var brokenSource = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1", user(), password());
        var brokenPresence = new PresenceService(new PresenceRepository(new JdbcTemplate(brokenSource)), "default", 30);
        var simulation = new SimulationService(new SimulationRepository(jdbc), new OfflineDetectorService(brokenPresence, stream), "default", 20);
        @SuppressWarnings("unchecked")
        org.springframework.kafka.core.KafkaTemplate<String, String> kafka = org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        var commands = new com.robot.fleet.domain.command.service.CommandService(kafka, new ObjectMapper(), "default", simulation,
                new com.robot.fleet.domain.realtime.RealtimeState(false));
        var request = new com.robot.fleet.domain.command.dto.CommandRequestDto();
        request.setRobotId("robot"); request.setCommand("stop");
        // when, then
        assertThatThrownBy(() -> commands.sendCommand(request)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
        org.mockito.Mockito.verifyNoInteractions(kafka);
    }

    @Test @DisplayName("미래 관측의 중복 수신은 최초 유효기간을 연장하지 않는다")
    void observe_FutureDuplicatePreservesFirstClampedTime() {
        // given
        var future = now().plusSeconds(3);
        first.observe(event(runtime, future));
        var original = jdbc.queryForObject("SELECT observed_at FROM robot_presence", OffsetDateTime.class);
        // when
        first.observe(event(runtime, future));
        first.observe(event(runtime, future.minusSeconds(1)));
        // then
        assertThat(jdbc.queryForObject("SELECT observed_at FROM robot_presence", OffsetDateTime.class)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT source_timestamp FROM robot_presence", OffsetDateTime.class)).isEqualTo(future);
        jdbc.update("UPDATE robot_presence SET observed_at = clock_timestamp() - INTERVAL '31' SECOND");
        first.observe(event(runtime, future));
        assertThat(second.online("robot", runtime)).isFalse();
    }

    @Test @DisplayName("오래된 트랜잭션에서도 관측과 heartbeat 만료를 현재 판정 시각으로 확인한다")
    void online_UsesDecisionClockInsideExistingTransaction() throws Exception {
        // given
        first.observe(event(runtime, now()));
        first.observe(new PoseTelemetryDto("pose", "static", now(), null, new PoseTelemetryDataDto(0, 0)));
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            var txJdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            txJdbc.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class);
            txJdbc.update("UPDATE simulation_robot SET reported_at = CURRENT_TIMESTAMP - INTERVAL '9' SECOND");
            // when
            txJdbc.execute("SELECT pg_sleep(1.2)");
            var txPresence = new PresenceRepository(txJdbc);
            // then
            assertThat(txPresence.online("default", "static", null, 1)).isFalse();
            assertThat(txPresence.online("default", "robot", runtime, 30)).isFalse();
            var txApi = new SimulationService(new SimulationRepository(txJdbc),
                    new OfflineDetectorService(new PresenceService(txPresence, "default", 30), stream), "default", 20);
            refused(txApi);
            connection.rollback();
        }
    }

    @Test @DisplayName("원본 시각 컬럼이 없는 구버전 API의 insert도 배포 전환 중 유지한다")
    void migrate_PreservesLegacyWriterCompatibility() {
        // given, when
        jdbc.update("INSERT INTO robot_presence(fleet_id,robot_id,runtime_id,observed_at) VALUES ('default','legacy','',clock_timestamp())");
        // then
        assertThat(jdbc.queryForObject("SELECT source_timestamp FROM robot_presence WHERE robot_id='legacy'", OffsetDateTime.class)).isNotNull();
        assertThat(second.online("legacy", null)).isTrue();
    }

    @Test @DisplayName("같거나 과거 시각의 관측은 행 버전을 바꾸지 않고 기존 온라인 판정을 유지한다")
    void observe_SkipsUnchangedWritesWithoutLosingReadiness() {
        // given
        var timestamp=now();
        assertThat(first.observe(event(runtime,timestamp))).isTrue();
        var original=jdbc.queryForObject("SELECT xmin::text FROM robot_presence",String.class);
        // when, then
        assertThat(first.observe(event(runtime,timestamp))).isTrue();
        assertThat(first.observe(event(runtime,timestamp.minusSeconds(1)))).isTrue();
        assertThat(jdbc.queryForObject("SELECT xmin::text FROM robot_presence",String.class)).isEqualTo(original);
        jdbc.update("UPDATE robot_presence SET observed_at=clock_timestamp()-INTERVAL '31' SECOND");
        assertThat(first.observe(event(runtime,timestamp))).isFalse();
    }

    @Test @DisplayName("로컬 전송 전 삭제와 runtime 교체를 다시 확인해 과거 관측을 보내지 않는다")
    void localPublisher_FencesTransitionsAfterObservation() {
        // given
        var messaging=org.mockito.Mockito.mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
        var localStream=org.mockito.Mockito.mock(TelemetryStreamService.class);
        var publisher=new com.robot.fleet.domain.realtime.LocalRealtimePublisher(localStream,messaging,first);
        var old=event(runtime,now());
        assertThat(first.observe(old)).isTrue();
        publisher.telemetry(UUID.randomUUID().toString(),old);
        org.mockito.Mockito.verify(localStream).broadcast(old);
        org.mockito.Mockito.clearInvocations(localStream);
        // when
        apiA.registerRuntime(UUID.randomUUID().toString());
        publisher.telemetry(UUID.randomUUID().toString(),old);
        // then
        org.mockito.Mockito.verifyNoInteractions(localStream);
        // given
        var current=apiA.list().getFirst().runtimeId();
        apiA.report(new SimulationReport("robot",1,"RUNNING",null,current));
        var fresh=event(current,now());
        assertThat(first.observe(fresh)).isTrue();
        // when
        apiA.delete("robot");
        publisher.telemetry(UUID.randomUUID().toString(),fresh);
        // then
        org.mockito.Mockito.verifyNoInteractions(localStream);
    }

}
