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
@Import({SimulationRepository.class, SimulationService.class})
@TestPropertySource(properties = {
        "spring.sql.init.schema-locations=classpath:db/migration/V4__add_simulation_registry.sql",
        "simulation.max-robots=3"})
class SimulationServiceTest {
    @Autowired SimulationService service;
    @Autowired JdbcTemplate jdbc;
    @MockBean OfflineDetectorService offline;
    private static final String RUNTIME = UUID.randomUUID().toString();

    @BeforeEach
    void prepare() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS telemetry_pose(robot_id VARCHAR(64))");
        jdbc.update("DELETE FROM simulation_robot");
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
        given(offline.isOffline("robot")).willReturn(true);
        assertThatThrownBy(() -> service.commandContext("robot")).isInstanceOf(ResponseStatusException.class);
        given(offline.isOffline("robot")).willReturn(false);
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
}
