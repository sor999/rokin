package com.robot.fleet.domain.simulation;

import com.robot.fleet.domain.simulation.dto.*;
import com.robot.fleet.domain.simulation.repository.DistributionRepository;
import com.robot.fleet.domain.simulation.service.DistributionService;
import static com.robot.fleet.domain.simulation.dto.DistributionDtos.*;
import java.util.List;
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

@EnabledIfEnvironmentVariable(named = "PRESENCE_TEST_URL", matches = ".+")
class DistributionPostgresTest {
    private String schema;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private SimulationRepository repository;
    private DistributionService apiA;
    private DistributionService apiB;
    private DistributionRepository distribution;

    private String url() {
        return System.getenv("PRESENCE_TEST_URL");
    }

    private String user() {
        return System.getenv().getOrDefault("PRESENCE_TEST_USER", "postgres");
    }

    private String password() {
        return System.getenv().getOrDefault("PRESENCE_TEST_PASSWORD", "rokin_test_only");
    }

    @BeforeEach
    void prepare() {
        schema = "distribution_test_" + UUID.randomUUID().toString().replace("-", "");
        var config = Flyway.configure().dataSource(url(), user(), password()).schemas(schema);
        config.getPluginRegister()
                .getPlugin(org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension.class)
                .setTransactionalLock(false);
        config.load().migrate();
        String separator = url().contains("?") ? "&" : "?";
        var source = new DriverManagerDataSource(
                url() + separator + "currentSchema=" + schema + "&ApplicationName=" + schema,
                user(), password());
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(5);
        repository = new SimulationRepository(jdbc);
        distribution = new DistributionRepository(jdbc);
        apiA = new DistributionService(distribution, repository, "default", true, 12000);
        apiB = new DistributionService(new DistributionRepository(new JdbcTemplate(source)),
                repository, "default", true, 12000);
        tx.executeWithoutResult(status -> {
            var registration = new SimulationService(repository, mock(OfflineDetectorService.class), "default", 20);
            for (int index = 1; index <= 6; index++) {
                registration.create(new CreateSimulationRequest("assigned_" + index, 0.0, 0.0, 1.0));
            }
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var connection = DriverManager.getConnection(url(), user(), password());
             var sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private Snapshot pulse(DistributionService api, String runtime, int capacity) {
        return tx.execute(status -> api.pulse(new PulseRequest(runtime, capacity)));
    }

    private String runtime() {
        return UUID.randomUUID().toString();
    }

    @Test
    void capacityAndExpiryPreserveUnaffectedExecutionIds() {
        String first = runtime();
        String second = runtime();
        var original = pulse(apiA, first, 3);
        var other = pulse(apiB, second, 3);
        assertThat(original.assignments()).hasSize(3);
        assertThat(other.assignments()).hasSize(3);
        assertThat(distribution.unassigned("default")).isEmpty();

        jdbc.update("""
                UPDATE simulation_execution_server
                SET lease_expires_at = clock_timestamp() - INTERVAL '1' SECOND
                WHERE runtime_id = ?
                """, second);
        var preserved = pulse(apiB, first, 3);
        assertThat(preserved.assignments()).isEqualTo(original.assignments());
        assertThat(distribution.unassigned("default")).hasSize(3);
        assertThat(pulse(apiA, second, 3).state()).isEqualTo("RETIRED");

        var recovered = pulse(apiB, runtime(), 3);
        assertThat(recovered.assignments()).hasSize(3);
        assertThat(recovered.assignments()).extracting(Assignment::executionId)
                .doesNotContainAnyElementsOf(other.assignments().stream().map(Assignment::executionId).toList());
        assertThat(recovered.assignments()).allMatch(assignment -> assignment.assignmentVersion() == 2);
    }

    @Test
    void releaseRequiresMatchingOwnerAndGenerationAndDrainingDoesNotReceiveNewWork() {
        String first = runtime();
        String second = runtime();
        var original = pulse(apiA, first, 6);
        pulse(apiB, second, 6);
        var robot = original.assignments().getFirst();
        tx.executeWithoutResult(status -> apiA.drain(first));
        // 이동 중이거나 관측이 불명확한 로봇은 정상 축소에서 강제 종료하지 않는다.
        assertThat(distribution.assignments("default", first)).allMatch(a -> a.state().equals("ASSIGNED"));

        tx.executeWithoutResult(status -> distribution.requestRelease("default", robot.robotId()));
        var stale = new ReleaseRequest(robot.robotId(), runtime(), robot.assignmentVersion());
        tx.executeWithoutResult(status -> apiB.released(new ReleaseBatch(first, List.of(stale))));
        assertThat(distribution.assignments("default", first)).hasSize(6);

        var valid = new ReleaseRequest(robot.robotId(), robot.executionId(), robot.assignmentVersion());
        tx.executeWithoutResult(status -> apiB.released(new ReleaseBatch(second, List.of(valid))));
        assertThat(distribution.assignments("default", first)).hasSize(6);
        tx.executeWithoutResult(status -> apiB.released(new ReleaseBatch(first, List.of(valid))));
        var moved = distribution.assignments("default", second).getFirst();
        assertThat(moved.robotId()).isEqualTo(robot.robotId());
        assertThat(moved.executionId()).isNotEqualTo(robot.executionId());
        assertThat(moved.assignmentVersion()).isEqualTo(robot.assignmentVersion() + 1);
        tx.executeWithoutResult(status -> apiB.released(new ReleaseBatch(first, List.of(valid))));
        assertThat(distribution.assignments("default", second)).containsExactly(moved);
    }

    @Test
    void balancingRequiresFreshIdleAndNeverReleasesMoreThanOneRobot() {
        String first = runtime();
        String second = runtime();
        var original = pulse(apiA, first, 6);
        var idle = original.assignments().getFirst();
        jdbc.update("""
                INSERT INTO robot_latest(robot_id, runtime_id, kind, event_id, observed_at, state)
                VALUES (?, ?, 'status', ?, clock_timestamp(), 'moving')
                """, idle.robotId(), idle.executionId(), UUID.randomUUID());
        pulse(apiB, second, 6);
        assertThat(distribution.releaseInProgress("default")).isFalse();

        jdbc.update("UPDATE robot_latest SET state = 'idle' WHERE robot_id = ?", idle.robotId());
        jdbc.update("""
                UPDATE simulation_robot_assignment SET last_command_at = clock_timestamp()
                WHERE robot_id = ?
                """, idle.robotId());
        pulse(apiB, second, 6);
        assertThat(distribution.releaseInProgress("default")).isFalse();

        jdbc.update("UPDATE simulation_robot_assignment SET last_command_at = NULL WHERE robot_id = ?", idle.robotId());
        pulse(apiA, first, 6);
        assertThat(distribution.assignments("default", first))
                .filteredOn(a -> a.state().equals("RELEASING"))
                .extracting(Assignment::robotId).containsExactly(idle.robotId());
        pulse(apiB, second, 6);
        assertThat(distribution.assignments("default", first)).hasSize(6);
        assertThat(distribution.assignments("default", second)).isEmpty();
    }

    @Test
    void deletionPreservesTombstoneAndDoesNotReassignDeletedRobot() {
        String first = runtime();
        var assignment = pulse(apiA, first, 6).assignments().getFirst();
        tx.executeWithoutResult(status -> repository.delete(assignment.robotId()));
        pulse(apiA, first, 6);
        var receipt = new ReleaseRequest(assignment.robotId(), assignment.executionId(), assignment.assignmentVersion());
        tx.executeWithoutResult(status -> apiA.released(new ReleaseBatch(first, List.of(receipt))));
        pulse(apiB, runtime(), 6);
        assertThat(distribution.assignments("default", first))
                .noneMatch(a -> a.robotId().equals(assignment.robotId()));
        assertThat(jdbc.queryForObject("SELECT runtime_state FROM simulation_robot WHERE robot_id = ?",
                String.class, assignment.robotId())).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulation_robot_assignment WHERE robot_id = ?",
                Integer.class, assignment.robotId())).isEqualTo(1);
    }

    @Test
    void invalidInputDisabledModeAndRollbackDoNotRegisterServers() {
        assertThatThrownBy(() -> pulse(apiA, "invalid", 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pulse(apiA, runtime(), 0)).isInstanceOf(IllegalArgumentException.class);
        var disabled = new DistributionService(distribution, repository, "default", false, 12000);
        assertThatThrownBy(() -> pulse(disabled, runtime(), 3))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        String failed = runtime();
        tx.executeWithoutResult(status -> {
            apiA.pulse(new PulseRequest(failed, 3));
            status.setRollbackOnly();
        });
        assertThat(distribution.used("default")).isFalse();
        assertThat(distribution.servers("default")).isEmpty();
        assertThat(distribution.unassigned("default")).isEmpty();
        assertThat(pulse(apiB, failed, 3).assignments()).hasSize(3);
    }

    @Test
    void concurrentApisWaitOnTheFleetLockAndRespectCapacity() throws Exception {
        String first = runtime();
        String second = runtime();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var winner = executor.submit(() -> tx.execute(status -> {
                repository.lockFleet("default");
                locked.countDown();
                try {
                    if (!release.await(4, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(error);
                }
                return apiA.pulse(new PulseRequest(first, 3));
            }));
            assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
            var contender = executor.submit(() -> pulse(apiB, second, 3));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean waiting = false;
            try {
                while (System.nanoTime() < deadline && !waiting) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                                WHERE application_name = ? AND wait_event_type = 'Lock')
                            """, Boolean.class, schema));
                    if (!waiting) Thread.sleep(10);
                }
                assertThat(waiting).isTrue();
            } finally {
                release.countDown();
            }
            assertThat(winner.get(4, TimeUnit.SECONDS).assignments()).hasSize(3);
            assertThat(contender.get(4, TimeUnit.SECONDS).assignments()).hasSize(3);
            assertThat(distribution.servers("default")).allMatch(server -> server.assignedCount() <= server.capacity());
            assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT execution_id) FROM simulation_robot_assignment", Integer.class))
                    .isEqualTo(6);
        }
    }
}
