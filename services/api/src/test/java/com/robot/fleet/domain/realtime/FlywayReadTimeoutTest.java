package com.robot.fleet.domain.realtime;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.sql.DriverManager;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class FlywayReadTimeoutTest {
    private Properties settings() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }

    @Test @DisplayName("Flyway의 DB 연결은 일반 조회의 socketTimeout에서 분리한다")
    void flyway_HasIndependentConnectionSettings() {
        var properties = settings();
        assertThat(properties.getProperty("spring.datasource.url")).contains("socketTimeout=2");
        assertThat(properties.getProperty("spring.flyway.url"))
                .contains("connectTimeout=2", "cancelSignalTimeout=1").doesNotContain("socketTimeout=");
        assertThat(properties.getProperty("spring.flyway.user")).isEqualTo(properties.getProperty("spring.datasource.username"));
        assertThat(properties.getProperty("spring.flyway.password")).isEqualTo(properties.getProperty("spring.datasource.password"));
    }

    @Test @Timeout(30)
    @EnabledIfEnvironmentVariable(named = "FENCE_TEST_URL", matches = ".+")
    @DisplayName("Flyway는 2초를 넘는 PostgreSQL 응답도 기다려 기동을 완료한다")
    void flyway_AllowsLongDatabaseWork() throws Exception {
        String base = System.getenv("FENCE_TEST_URL");
        String user = System.getenv().getOrDefault("FENCE_TEST_USER", "postgres");
        String password = System.getenv().getOrDefault("FENCE_TEST_PASSWORD", "rokin_test_only");
        String parameters = settings().getProperty("spring.flyway.url").split("\\?", 2)[1];
        String schema = "flyway_timeout_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(base, user, password); var sql = admin.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                var result = Flyway.configure().dataSource(base + (base.contains("?") ? "&" : "?") + parameters, user, password)
                        .schemas(schema).locations(new String[0]).initSql("SELECT pg_sleep(3)").load().migrate();
                assertThat(result.success).isTrue();
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
