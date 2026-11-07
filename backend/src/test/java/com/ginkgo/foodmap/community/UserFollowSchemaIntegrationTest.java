package com.ginkgo.foodmap.community;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.*;
import java.util.*;

/** V32真实MySQL迁移验证，仅使用显式回环随机隔离库；不连接业务库或启动业务定时任务。 */
@EnabledIfSystemProperty(named = "follows.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserFollowSchemaIntegrationTest {
    private String database;
    private boolean created;
    private JdbcTemplate db;
    private Map<String, Object> legacyBefore;
    private int migrated;

    @BeforeAll
    void initialize() throws SQLException {
        database = "ginkgo_follows_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        db = new JdbcTemplate(new DriverManagerDataSource(url(database), username(), password()));
        Flyway.configure()
                .dataSource(url(database), username(), password())
                .locations("classpath:db/migration")
                .target("31")
                .load()
                .migrate();
        db.update(
                "INSERT INTO users(id,username,email,password_hash,email_verified,bio,row_version,credential_version) VALUES(11,'favorite_owner','owner@example.test','fixture-hash',TRUE,'原简介',7,4),(22,'favorite_other','other@example.test','fixture-hash',TRUE,'',0,0)");
        db.update("UPDATE users SET favorites_public=TRUE WHERE id=11");
        legacyBefore = db.queryForMap("SELECT * FROM users WHERE id=11");
        migrated = flyway().migrate().migrationsExecuted;
    }

    @BeforeEach
    void resetRelations() {
        db.update("DELETE FROM user_follows");
        db.update("UPDATE users SET following_public=FALSE,followers_public=FALSE WHERE id=22");
    }

    @AfterAll
    void cleanup() throws SQLException {
        if (!created) {
            return;
        }
        if (!database.matches("ginkgo_follows_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test database");
        }
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void upgradePreservesUserFieldsIncludingExistingFavoritePrivacy() {
        assertThat(migrated).isEqualTo(1);
        var current = db.queryForMap("SELECT * FROM users WHERE id=11");
        assertThat(current.remove("following_public")).isIn(false, 0);
        assertThat(current.remove("followers_public")).isIn(false, 0);
        assertThat(current).isEqualTo(legacyBefore);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_follows", Long.class)).isZero();
    }

    @Test
    void directedPairsAreUniqueAndMutualFollowsAreIndependent() {
        add(11, 22);
        add(22, 11);
        assertThatThrownBy(() -> add(11, 22)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_follows", Long.class)).isEqualTo(2);
    }

    @Test
    void validatesUserIdsSelfFollowAndLogicalReferenceBoundary() {
        for (String value : List.of("0", "-1", "9223372036854775808", "NULL")) {
            invalid(
                    () ->
                            db.update(
                                    "INSERT INTO user_follows(follower_user_id,followed_user_id) VALUES("
                                            + value
                                            + ",22)"));
            invalid(
                    () ->
                            db.update(
                                    "INSERT INTO user_follows(follower_user_id,followed_user_id) VALUES(11,"
                                            + value
                                            + ")"));
        }
        invalid(() -> add(11, 11));
        add(11, 999);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name='user_follows'",
                                Long.class))
                .isZero();
    }

    @Test
    void activeFlagAndTimestampsAreConstrained() {
        add(11, 22);
        for (String value : List.of("2", "-1", "NULL")) {
            invalid(() -> db.update("UPDATE user_follows SET active=" + value));
        }
        invalid(() -> db.update("UPDATE user_follows SET updated_at=created_at-INTERVAL 1 SECOND"));
    }

    @Test
    void cancelAndRefollowKeepOriginalIdentityAndFirstCreatedTime() {
        add(11, 22);
        var before = db.queryForMap("SELECT id,created_at FROM user_follows");
        db.update("UPDATE user_follows SET active=FALSE");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM user_follows WHERE active=TRUE", Long.class))
                .isZero();
        db.update("UPDATE user_follows SET active=TRUE");
        assertThat(db.queryForMap("SELECT id,created_at FROM user_follows")).isEqualTo(before);
        assertThat(flyway().migrate().migrationsExecuted).isZero();
        assertThat(db.queryForMap("SELECT id,created_at FROM user_follows")).isEqualTo(before);
    }

    @Test
    void listPrivacyFlagsAreIndependentAndDefaultPrivateForNewUsers() {
        db.update(
                "INSERT INTO users(id,username,email,password_hash) VALUES(33,'follow_new','new@example.test','fixture-hash')");
        assertThat(
                        db.queryForObject(
                                "SELECT following_public OR followers_public FROM users WHERE id=33",
                                Boolean.class))
                .isFalse();
        for (String field : List.of("following_public", "followers_public")) {
            for (String value : List.of("2", "-1", "NULL")) {
                invalid(
                        () ->
                                db.update(
                                        "UPDATE users SET "
                                                + field
                                                + "="
                                                + value
                                                + " WHERE id=22"));
            }
        }
        db.update("UPDATE users SET following_public=TRUE WHERE id=22");
        assertThat(
                        db.queryForObject(
                                "SELECT followers_public FROM users WHERE id=22", Boolean.class))
                .isFalse();
        db.update("UPDATE users SET followers_public=TRUE,following_public=FALSE WHERE id=22");
        assertThat(
                        db.queryForObject(
                                "SELECT following_public FROM users WHERE id=22", Boolean.class))
                .isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT favorites_public FROM users WHERE id=11", Boolean.class))
                .isTrue();
    }

    @Test
    void indexesSupportBothDirectionsAndActiveStateCounts() {
        add(11, 22);
        add(11, 44);
        add(22, 11);
        db.update("UPDATE user_follows SET active=FALSE WHERE followed_user_id=44");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM user_follows WHERE follower_user_id=11 AND active=TRUE",
                                Long.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM user_follows WHERE followed_user_id=11 AND active=TRUE",
                                Long.class))
                .isEqualTo(1);
        for (String key : List.of("idx_user_follows_follower", "idx_user_follows_followed")) {
            assertThat(db.queryForList("SHOW INDEX FROM user_follows WHERE Key_name='" + key + "'"))
                    .hasSize(3);
        }
    }

    /** MySQL CHECK的HY000可能被Spring映射为未分类异常，核验实际数据库错误码而非翻译类型。 */
    private void invalid(Runnable action) {
        try {
            action.run();
            fail("Expected database constraint rejection");
        } catch (DataAccessException exception) {
            assertThat(exception.getMostSpecificCause()).isInstanceOf(SQLException.class);
            assertThat(((SQLException) exception.getMostSpecificCause()).getErrorCode())
                    .isIn(3819, 1048, 1264);
        }
    }

    private void add(long follower, long followed) {
        db.update(
                "INSERT INTO user_follows(follower_user_id,followed_user_id) VALUES(?,?)",
                follower,
                followed);
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(url(database), username(), password())
                .locations("classpath:db/migration")
                .target("32")
                .load();
    }

    private String username() {
        return System.getenv().getOrDefault("FOLLOWS_TEST_DB_USERNAME", "root");
    }

    private String password() {
        return Objects.requireNonNull(
                System.getenv("FOLLOWS_TEST_DB_PASSWORD"), "Set isolated test password");
    }

    private String url(String name) {
        var server =
                System.getenv()
                        .getOrDefault("FOLLOWS_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Loopback only");
        }
        return server + name + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), username(), password());
    }
}
