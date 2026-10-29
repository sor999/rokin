package com.robot.fleet.domain.simulation;

import com.robot.fleet.domain.simulation.dto.*;
import com.robot.fleet.domain.simulation.repository.SimulationRepository;
import com.robot.fleet.domain.simulation.service.SimulationService;
import com.robot.fleet.domain.telemetry.service.OfflineDetectorService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;

@DataJpaTest
@org.springframework.test.context.ActiveProfiles("test")
@Import({SimulationRepository.class, SimulationService.class, com.robot.fleet.domain.robot.service.RobotQueryService.class})
@TestPropertySource(properties = {
        "spring.sql.init.schema-locations=classpath:db/migration/V4__add_simulation_registry.sql,classpath:simulation-runtime-test.sql",
        "simulation.max-robots=3"})
class SimulationServiceTest {
    @Autowired SimulationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.robot.fleet.domain.robot.service.RobotQueryService queries;
    @MockBean OfflineDetectorService offline;
    private static final String RUNTIME = UUID.randomUUID().toString();

    @BeforeEach
    void prepare() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS telemetry_pose(robot_id VARCHAR(64))");
        jdbc.update("DELETE FROM simulation_robot");
        jdbc.update("DELETE FROM simulation_runtime_session");
        service.registerRuntime(RUNTIME);
    }

    private CreateSimulationRequest request(String id) {
        return new CreateSimulationRequest(id, 0.0, 0.0, 1.0);
    }

    @Test @DisplayName("설정을 저장하고 삭제 요청을 반복해도 버전은 한 번만 증가한다")
    void createAndDelete_PreservesTombstone() {
        // given
        SimulationDto created = service.create(request("robot_new"));
        // when
        SimulationDto deleted = service.delete(created.robotId());
        SimulationDto repeated = service.delete(created.robotId());
        // then
        assertThat(deleted.version()).isEqualTo(2);
        assertThat(repeated.version()).isEqualTo(2);
        assertThat(service.list()).singleElement().extracting(SimulationDto::desiredState).isEqualTo("DELETED");
        assertThatThrownBy(() -> service.create(request("robot_new"))).isInstanceOf(ResponseStatusException.class);
    }

    @Test @DisplayName("실행 상한과 중복 ID를 DB 목록 기준으로 거부한다")
    void create_RejectsDuplicateAndCapacity() {
        // given
        service.create(request("first")); service.create(request("second")); service.create(request("third"));
        // when, then
        assertThatThrownBy(() -> service.create(request("first"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.create(request("fourth"))).isInstanceOf(ResponseStatusException.class);
        service.delete("first");
        assertThatThrownBy(() -> service.create(request("fourth"))).isInstanceOf(ResponseStatusException.class);
        service.report(new SimulationReport("first", 2, "DELETED", null, RUNTIME));
        assertThat(service.create(request("fourth")).state()).isEqualTo("STARTING");
    }

    @Test @DisplayName("잘못된 좌표와 속도 및 ROS 토픽에 사용할 수 없는 ID를 거부한다")
    void create_RejectsInvalidParameters() {
        // given
        var inputs = java.util.List.of(new CreateSimulationRequest("1robot", 0.0, 0.0, 1.0),
                new CreateSimulationRequest("robot-x", 0.0, 0.0, 1.0),
                new CreateSimulationRequest("robot", Double.NaN, 0.0, 1.0),
                new CreateSimulationRequest("robot", 0.0, 0.0, 0.0),
                new CreateSimulationRequest("robot", 0.0, 0.0, 11.0));
        // when, then
        inputs.forEach(input -> assertThatThrownBy(() -> service.create(input)).isInstanceOf(IllegalArgumentException.class));
        assertThat(service.list()).isEmpty();
    }

    @Test @DisplayName("준비·온라인·heartbeat가 확인된 실행 인스턴스만 명령을 접수한다")
    void commandContext_GatesReadinessAndHeartbeat() {
        // given
        service.create(request("robot"));
        assertThatThrownBy(() -> service.commandContext("robot")).isInstanceOf(ResponseStatusException.class);
        service.report(new SimulationReport("robot", 1, "RUNNING", null, RUNTIME));
        given(offline.isOffline("robot", RUNTIME)).willReturn(true);
        assertThatThrownBy(() -> service.commandContext("robot")).isInstanceOf(ResponseStatusException.class);
        given(offline.isOffline("robot", RUNTIME)).willReturn(false);
        // when
        var context = service.commandContext("robot");
        // then
        assertThat(context).containsEntry("simulation_runtime_id", RUNTIME).containsEntry("simulation_version", 1L);
        jdbc.update("UPDATE simulation_robot SET reported_at = ?", OffsetDateTime.now().minusSeconds(20));
        assertThat(service.list().getFirst().state()).isEqualTo("STARTING");
        assertThatThrownBy(() -> service.commandContext("robot")).isInstanceOf(ResponseStatusException.class);
    }

    @Test @DisplayName("삭제 이후 도착한 이전 버전의 실행 보고가 로봇을 되살리지 않는다")
    void report_IgnoresStaleVersion() {
        // given
        service.create(request("robot")); service.delete("robot");
        // when
        service.report(new SimulationReport("robot", 1, "RUNNING", null, RUNTIME));
        // then
        assertThat(service.list().getFirst().state()).isEqualTo("DELETING");
        assertThatThrownBy(() -> service.commandContext("robot")).isInstanceOf(ResponseStatusException.class);
    }

    @Test @DisplayName("시작 시 기본 로봇은 한 번만 저장하고 삭제한 기본 로봇도 재생성하지 않는다")
    void run_SeedsOnlyOnce() {
        // given
        service.run(null);
        service.delete("robot_1");
        // when
        service.run(null);
        // then
        assertThat(service.list()).hasSize(3);
        assertThat(service.list().stream().filter(r -> r.robotId().equals("robot_1")).findFirst().orElseThrow()
                .desiredState()).isEqualTo("DELETED");
    }
    @Test @DisplayName("이전 실행의 지연 보고와 재등록은 새 실행 인스턴스를 덮어쓰지 않는다")
    void report_FencesRetiredRuntime() {
        // given
        service.create(request("robot"));
        service.report(new SimulationReport("robot", 1, "RUNNING", null, RUNTIME));
        String next = UUID.randomUUID().toString();
        service.registerRuntime(next);
        assertThat(service.list().getFirst().state()).isEqualTo("STARTING");
        // when
        service.report(new SimulationReport("robot", 1, "RUNNING", null, next));
        service.report(new SimulationReport("robot", 1, "FAILED", "old", RUNTIME));
        // then
        assertThat(service.list().getFirst().runtimeId()).isEqualTo(next);
        assertThat(service.list().getFirst().state()).isEqualTo("RUNNING");
        assertThatThrownBy(() -> service.registerRuntime(RUNTIME)).isInstanceOf(ResponseStatusException.class);
        service.registerRuntime(next);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM simulation_runtime_session", Integer.class)).isEqualTo(2);
    }

    @Test @DisplayName("pose 없이 배터리·상태·Ack만 남은 ID도 재사용할 수 없다")
    void create_RejectsIdsWithAnyHistory() {
        // given
        jdbc.update("INSERT INTO telemetry_battery(event_id,timestamp,robot_id,level,created_at,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), OffsetDateTime.now(), "battery_only", 90);
        jdbc.update("INSERT INTO telemetry_status(event_id,timestamp,robot_id,state,created_at,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), OffsetDateTime.now(), "status_only", "idle");
        jdbc.update("INSERT INTO robot_ack(cmd_id,timestamp,robot_id,status,created_at,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), OffsetDateTime.now(), "ack_only", "done");
        // when, then
        for (String id : java.util.List.of("battery_only", "status_only", "ack_only")) {
            assertThatThrownBy(() -> service.create(request(id))).isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test @DisplayName("안전한 시작 좌표 경계는 허용하고 정밀도를 잃는 큰 좌표는 거부한다")
    void create_BoundsStartingCoordinates() {
        // given
        var boundary = new CreateSimulationRequest("edge", -1000000.0, 1000000.0, 1.0);
        // when, then
        assertThat(service.create(boundary).startX()).isEqualTo(-1000000.0);
        assertThatThrownBy(() -> service.create(new CreateSimulationRequest("large", 1e16, 0.0, 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("REST 목록은 현재 실행의 pose만 조회하고 이전 실행의 배터리·상태를 섞지 않는다")
    void getAllRobots_FiltersSourceRuntime() {
        // given
        service.create(request("robot"));
        service.report(new SimulationReport("robot", 1, "RUNNING", null, RUNTIME));
        jdbc.update("INSERT INTO telemetry_pose(event_id,timestamp,robot_id,x,y,simulation_runtime_id,created_at,updated_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), OffsetDateTime.now(), "robot", 99, 99, "old-runtime");
        jdbc.update("INSERT INTO telemetry_pose(event_id,timestamp,robot_id,x,y,simulation_runtime_id,created_at,updated_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), OffsetDateTime.now().minusSeconds(1), "robot", 1, 2, RUNTIME);
        jdbc.update("INSERT INTO telemetry_battery(event_id,timestamp,robot_id,level,simulation_runtime_id,created_at,updated_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), OffsetDateTime.now(), "robot", 40, "old-runtime");
        // when
        var dto = queries.getAllRobots().getFirst();
        // then
        assertThat(dto.runtimeId()).isEqualTo(RUNTIME);
        assertThat(dto.pose().x()).isEqualTo(1);
        assertThat(dto.battery()).isNull();
    }

    private void pose(String robot, String runtime, double x) {
        jdbc.update("INSERT INTO telemetry_pose(event_id,timestamp,robot_id,x,y,simulation_runtime_id,created_at,updated_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), OffsetDateTime.now(), robot, x, 2, runtime);
    }

    private void battery(String robot, String runtime, double level) {
        jdbc.update("INSERT INTO telemetry_battery(event_id,timestamp,robot_id,level,simulation_runtime_id,created_at,updated_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), OffsetDateTime.now(), robot, level, runtime);
    }

    @Test @DisplayName("최신 pose·battery는 pending·이전 실행·현재 실행·정적 로봇을 구분한다")
    void latestTelemetry_FiltersManagedRuntimeAndPreservesStatic() {
        // given
        jdbc.update("DELETE FROM simulation_runtime_session");
        service.create(request("managed"));
        pose("managed", "old", 99); battery("managed", "old", 20);
        // when, then: pending
        assertThat(queries.getLatestPose("managed")).isNull();
        assertThat(queries.getLatestBattery("managed")).isNull();
        service.registerRuntime(RUNTIME);
        // when, then: only retired rows
        assertThat(queries.getLatestPose("managed")).isNull();
        assertThat(queries.getLatestBattery("managed")).isNull();
        pose("managed", RUNTIME, 3); battery("managed", RUNTIME, 90);
        // when, then: current rows
        assertThat(queries.getLatestPose("managed").x()).isEqualTo(3);
        assertThat(queries.getLatestBattery("managed").level()).isEqualTo(90);
        pose("static", null, 5); battery("static", null, 80);
        // when, then: unregistered static rows
        assertThat(queries.getLatestPose("static").x()).isEqualTo(5);
        assertThat(queries.getLatestBattery("static").level()).isEqualTo(80);
    }

    @Test @DisplayName("텔레메트리 수신은 현재 런타임과 삭제 의도를 기준으로 허용한다")
    void acceptsTelemetry_FencesRetiredAndDeletedRobots() {
        // given
        service.create(request("managed"));
        // when, then
        assertThat(service.acceptsTelemetry("managed", RUNTIME)).isTrue();
        assertThat(service.acceptsTelemetry("managed", "old")).isFalse();
        service.delete("managed");
        assertThat(service.acceptsTelemetry("managed", RUNTIME)).isFalse();
        assertThat(service.acceptsTelemetry("static", null)).isTrue();
        assertThat(service.acceptsTelemetry("unregistered", RUNTIME)).isFalse();
    }

}
