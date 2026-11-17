package com.foodmap.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 仅使用本次随机回环库，验证旧态度迁移及正文、评分、请求摘要和审计时间不变。 */
@EnabledIfSystemProperty(named = "review.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewRecommendationSchemaIntegrationTest {
    private String database;
    private boolean created;
    private JdbcTemplate jdbc;
    private List<Map<String, Object>> before;
    private int migrated;
    private static final String SNAPSHOT =
            "SELECT"
                + " id,restaurant_id,window_id,author_user_id,taste_score,value_score,service_score,ambience_score,overall_score,body,HEX(client_request_id)"
                + " request_id,HEX(request_hash) request_hash,created_at,updated_at FROM reviews"
                + " ORDER BY id";

    /** 先构造真实V45二态记录，按当前全部迁移升级，测试不能手工改写旧摘要来绕过兼容问题。 */
    @BeforeAll
    void prepare() throws Exception {
        database = "foodmap_review_attitude_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), username(), password());
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        migration("45").migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url(database), username(), password()));
        for (int id = 1; id <= 2; id++) {
            jdbc.update(
                    "INSERT INTO"
                        + " reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,"
                        + "is_recommended,body,client_request_id,request_hash,created_at,updated_at)"
                        + " VALUES(?,101,11,8,7,6,5,?,'历史态度迁移测试正文',UUID_TO_BIN(?),UNHEX(REPEAT('ab',32)),'2025-01-01"
                        + " 00:00:00.123456','2025-01-02 00:00:00.654321')",
                    id,
                    id == 1,
                    UUID.randomUUID().toString());
        }
        before = jdbc.queryForList(SNAPSHOT);
        migrated = migration(null).migrate().migrationsExecuted;
    }

    /** 只删除本次确实创建的随机库，禁止清理开发数据库。 */
    @AfterAll
    void cleanup() throws Exception {
        if (created && database.matches("foodmap_review_attitude_it_[a-f0-9]{32}")) {
            try (var connection = DriverManager.getConnection(url(""), username(), password());
                    var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE `" + database + "`");
            }
        }
    }

    @Test
    void backfillsBinaryAttitudesWithoutChangingOtherColumnsAndRepeatsSafely() {
        assertThat(migrated).isEqualTo(1);
        assertThat(jdbc.queryForList(SNAPSHOT)).isEqualTo(before);
        assertThat(
                        jdbc.queryForList(
                                "SELECT recommendation FROM reviews ORDER BY id", String.class))
                .containsExactly("RECOMMENDED", "NOT_RECOMMENDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.columns WHERE"
                                        + " table_schema=DATABASE() AND table_name='reviews' AND"
                                        + " column_name='is_recommended'",
                                Integer.class))
                .isZero();
        assertThat(migration(null).migrate().migrationsExecuted).isZero();
        migration(null).validate();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECOMMENDED", "NEUTRAL", "NOT_RECOMMENDED"})
    void acceptsAllThreeAttitudesWithoutChangingScore(String attitude) {
        try {
            jdbc.update("UPDATE reviews SET recommendation=? WHERE id=1", attitude);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT overall_score FROM reviews WHERE id=1",
                                    BigDecimal.class))
                    .isEqualByComparingTo("6.50");
        } finally {
            jdbc.update(
                    "UPDATE reviews SET recommendation='RECOMMENDED',updated_at='2025-01-02"
                            + " 00:00:00.654321' WHERE id=1");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "''", "'neutral'", "'NEUTRAL '", "'UNKNOWN'", "0", "1"})
    void rejectsMissingOrNonCanonicalAttitudes(String value) {
        var failure =
                assertThrows(
                        DataAccessException.class,
                        () ->
                                jdbc.update(
                                        "UPDATE reviews SET recommendation="
                                                + value
                                                + " WHERE id=1"));
        assertThat(failure.getMostSpecificCause()).isInstanceOf(SQLException.class);
        assertThat(((SQLException) failure.getMostSpecificCause()).getErrorCode()).isIn(3819, 1048);
    }

    private Flyway migration(String target) {
        var configuration =
                Flyway.configure()
                        .dataSource(url(database), username(), password())
                        .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private String url(String name) {
        String server =
                System.getenv()
                        .getOrDefault("REVIEW_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Only loopback MySQL is allowed");
        }
        return server + name + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private String username() {
        return System.getenv().getOrDefault("REVIEW_TEST_DB_USERNAME", "root");
    }

    private String password() {
        return Objects.requireNonNull(System.getenv("REVIEW_TEST_DB_PASSWORD"));
    }
}
