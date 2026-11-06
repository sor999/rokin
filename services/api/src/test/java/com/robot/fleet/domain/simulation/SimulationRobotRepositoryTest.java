package com.robot.fleet.domain.simulation;

import com.robot.fleet.domain.simulation.dto.SimulationDto;
import com.robot.fleet.domain.simulation.repository.SimulationRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Import(SimulationRepository.class)
class SimulationRobotRepositoryTest {
    @Autowired SimulationRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Fleet 격리와 로봇 정렬 및 삭제 완료만 제외하는 필터를 보존한다")
    void findAll_PreservesFleetOrderingAndActiveMeaning() {
        // given
        repository.insert("alpha", "z_robot", 1, 2, 3);
        repository.insert("alpha", "a_robot", 4, 5, 6);
        repository.insert("alpha", "deleted", 0, 0, 1);
        repository.insert("beta", "other", 0, 0, 1);
        repository.delete("a_robot");
        jdbc.update("UPDATE simulation_robot SET runtime_state = 'DELETED' WHERE robot_id = 'deleted'");
        // when
        var all = repository.findAll("alpha");
        var active = repository.findAll("alpha", true);
        // then
        assertThat(all).extracting(SimulationDto::robotId).containsExactly("a_robot", "deleted", "z_robot");
        assertThat(active).extracting(SimulationDto::robotId).containsExactly("a_robot", "z_robot");
        assertThat(repository.countActive("alpha")).isEqualTo(2);
        assertThat(repository.countActive("missing")).isZero();
        assertThat(repository.find("beta", "z_robot")).isEmpty();
        assertThat(repository.find("alpha", "missing")).isEmpty();
        assertThat(repository.findOwner("other")).contains("beta");
        assertThat(repository.findOwner("missing")).isEmpty();
    }

    @Test
    @DisplayName("같은 영속성 컨텍스트에서도 JDBC 갱신 직후의 상태와 nullable 필드를 조회한다")
    void find_ReflectsJdbcUpdatesWithoutStaleEntities() {
        // given
        repository.insert("alpha", "robot", -1.5, 2.5, 3);
        var original = repository.find("alpha", "robot").orElseThrow();
        var reportedAt = OffsetDateTime.parse("2026-11-06T12:00:00+09:00");
        // when
        jdbc.update("""
                UPDATE simulation_robot SET runtime_state = 'FAILED', config_version = 7,
                    runtime_id = 'runtime', error = 'failure', reported_at = ? WHERE robot_id = 'robot'
                """, reportedAt);
        var updated = repository.find("alpha", "robot").orElseThrow();
        repository.delete("robot");
        var deleted = repository.find("alpha", "robot").orElseThrow();
        // then
        assertThat(original.runtimeId()).isNull();
        assertThat(original.error()).isNull();
        assertThat(original.reportedAt()).isNull();
        assertThat(updated.startX()).isEqualTo(-1.5);
        assertThat(updated.startY()).isEqualTo(2.5);
        assertThat(updated.speed()).isEqualTo(3);
        assertThat(updated.state()).isEqualTo("FAILED");
        assertThat(updated.version()).isEqualTo(7);
        assertThat(updated.runtimeId()).isEqualTo("runtime");
        assertThat(updated.error()).isEqualTo("failure");
        assertThat(updated.reportedAt().toInstant()).isEqualTo(reportedAt.toInstant());
        assertThat(deleted.state()).isEqualTo("DELETING");
        assertThat(deleted.version()).isEqualTo(8);
        assertThat(deleted.reportedAt()).isNull();
        assertThat(deleted.error()).isNull();
    }

    @Test
    @DisplayName("목록은 로봇 수와 무관하게 단일 SELECT이며 엔티티를 적재하지 않는다")
    void findAll_UsesOneQueryWithoutEntityHydration() {
        // given
        for (int i = 0; i < 20; i++) repository.insert("alpha", "robot_" + i, i, i, 1);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        // when
        var result = repository.findAll("alpha");
        // then
        assertThat(result).hasSize(20);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("JPA 트랜잭션에서 JDBC 쓰기를 즉시 조회하고 롤백하면 등록도 사라진다")
    void find_SharesTransactionAndRollsBackJdbcWrites() {
        // given
        var tx = new TransactionTemplate(transactionManager);
        // when
        tx.executeWithoutResult(status -> {
            repository.insert("alpha", "rolled_back", 0, 0, 1);
            assertThat(repository.find("alpha", "rolled_back")).isPresent();
            assertThat(repository.findOwner("rolled_back")).contains("alpha");
            status.setRollbackOnly();
        });
        // then
        tx.executeWithoutResult(status -> {
            assertThat(repository.find("alpha", "rolled_back")).isEmpty();
            assertThat(repository.findOwner("rolled_back")).isEmpty();
            assertThat(repository.countActive("alpha")).isZero();
        });
    }
}
