package com.robot.fleet.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "MIGRATION_TEST_URL", matches = ".+")
class AckMigrationTest {

    @Test
    @DisplayName("빈 PostgreSQL에 Flyway 전체 스키마와 Ack 상태 제약을 생성한다.")
    void migrate_EmptyDatabase() throws Exception {
        String schema = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connection()) {
            try {
                Flyway flyway = flyway(schema);
                flyway.migrate();
                assertThat(flyway.info().current().getVersion().toString()).isEqualTo("3");
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT count(*) FROM " + schema + ".robot_ack_state")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isZero();
                }
                assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        }
    }

    @Test
    @DisplayName("기존 중복 Ack 이력을 보존하고 첫 종료 상태를 복원한다.")
    void migrate_ExistingHistory() throws Exception {
        String schema = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connection()) {
            try {
                Flyway.configure().configuration(flyway(schema).getConfiguration()).target("2").load().migrate();
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET search_path TO " + schema);
                    statement.execute("""
                            INSERT INTO robot_ack (cmd_id, timestamp, robot_id, status)
                            VALUES ('00000000-0000-0000-0000-000000000001', '2026-01-01T00:00:00Z', 'robot-1', 'accepted'),
                                   ('00000000-0000-0000-0000-000000000001', '2026-01-01T00:00:00Z', 'robot-1', 'accepted'),
                                   ('00000000-0000-0000-0000-000000000001', '2026-01-01T00:00:01Z', 'robot-1', 'failed'),
                                   ('00000000-0000-0000-0000-000000000001', '2026-01-01T00:00:01Z', 'robot-1', 'done'),
                                   ('00000000-0000-0000-0000-000000000002', '2026-01-01T00:00:00Z', 'robot-1', 'accepted')
                            """);
                }
                flyway(schema).migrate();
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT count(*) FROM robot_ack")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(5);
                }
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT status FROM robot_ack_state ORDER BY cmd_id")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("failed");
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("accepted");
                    assertThat(result.next()).isFalse();
                }
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        }
    }

    @Test
    @DisplayName("Worker가 생성한 기존 스키마를 Flyway baseline으로 인계한다.")
    void migrate_WorkerOwnedSchema() throws Exception {
        String schema = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connection()) {
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA " + schema);
                    statement.execute("SET search_path TO " + schema);
                    for (String migration : new String[]{"V1__create_telemetry_tables.sql", "V2__add_updated_at_columns.sql"}) {
                        try (java.io.InputStream resource = getClass().getResourceAsStream("/db/migration/" + migration)) {
                            assertThat(resource).isNotNull();
                            statement.execute(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                    statement.execute("CREATE UNIQUE INDEX uq_ack_dedup ON robot_ack(cmd_id, status, timestamp)");
                    statement.execute("INSERT INTO robot_ack(cmd_id,timestamp,robot_id,status) VALUES ('00000000-0000-0000-0000-000000000003', NOW(), 'robot-1', 'done')");
                }
                Flyway flyway = Flyway.configure().configuration(flyway(schema).getConfiguration())
                        .baselineOnMigrate(true).load();
                flyway.migrate();
                assertThat(flyway.info().current().getVersion().toString()).isEqualTo("3");
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT status FROM robot_ack_state")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("done");
                    assertThat(result.next()).isFalse();
                }
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        }
    }

    private Flyway flyway(String schema) {
        return Flyway.configure()
                .dataSource(System.getenv("MIGRATION_TEST_URL"), testUser(), testPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load();
    }

    private String testUser() {
        return System.getenv().getOrDefault("MIGRATION_TEST_USER", "postgres");
    }

    private String testPassword() {
        return System.getenv().getOrDefault("MIGRATION_TEST_PASSWORD", "rokin_test_only");
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(System.getenv("MIGRATION_TEST_URL"), testUser(), testPassword());
    }
}
