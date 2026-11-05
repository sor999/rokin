package com.robot.fleet.domain.realtime;

import com.robot.fleet.domain.simulation.repository.SimulationRepository;
import com.robot.fleet.domain.simulation.service.SimulationService;
import com.robot.fleet.domain.telemetry.service.OfflineDetectorService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "FENCE_TEST_URL", matches = ".+")
class TelemetryFenceDeadlineTest {
    @Test @Timeout(10)
    @DisplayName("느린 PostgreSQL fence 검증을 transaction deadline으로 취소한다")
    void acceptsTelemetry_CancelsSlowFenceQuery() throws Exception {
        // given: 별도 테스트 DB의 임시 schema에만 지연 view를 만든다.
        String url = System.getenv("FENCE_TEST_URL");
        String user = System.getenv().getOrDefault("FENCE_TEST_USER", "postgres");
        String password = System.getenv().getOrDefault("FENCE_TEST_PASSWORD", "rokin_test_only");
        String schema = "fence_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(url, user, password); var sql = admin.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE VIEW " + schema + ".simulation_robot AS SELECT 'robot_1'::text AS robot_id, "
                        + "'default'::text AS fleet_id, 'RUNNING'::text AS desired_state FROM pg_sleep(5)");
                sql.execute("CREATE TABLE " + schema + ".simulation_runtime_session(runtime_id text, fleet_id text, epoch bigint, lease_expires_at timestamptz)");
                var dataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                        + "currentSchema=" + schema + "&socketTimeout=2&cancelSignalTimeout=1", user, password);
                var target = new SimulationService(new SimulationRepository(new JdbcTemplate(dataSource)),
                        mock(OfflineDetectorService.class), "default", 20);
                var advice = new TransactionInterceptor();
                advice.setTransactionManager(new DataSourceTransactionManager(dataSource));
                advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
                var factory = new ProxyFactory(target);
                factory.setProxyTargetClass(true);
                factory.addAdvice(advice);
                var service = (SimulationService) factory.getProxy();
                // when, then
                long started = System.nanoTime();
                assertThatThrownBy(() -> service.acceptsTelemetry("robot_1", "current"))
                        .isInstanceOf(QueryTimeoutException.class);
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(500L, 4000L);
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
