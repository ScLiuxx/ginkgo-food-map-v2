package com.ginkgo.foodmap.restaurant;

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

/** V31真实MySQL迁移验证，仅使用显式回环随机隔离库；不连接业务库或启动业务定时任务。 */
@EnabledIfSystemProperty(named = "favorites.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestaurantFavoriteSchemaIntegrationTest {
    private String database;
    private boolean created;
    private JdbcTemplate db;
    private Map<String, Object> legacyBefore;
    private int migrated;

    @BeforeAll
    void initialize() throws SQLException {
        database = "ginkgo_favorites_it_" + UUID.randomUUID().toString().replace("-", "");
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
                .target("30")
                .load()
                .migrate();
        db.update(
                "INSERT INTO users(id,username,email,password_hash,email_verified,bio,row_version,credential_version) VALUES(11,'favorite_owner','owner@example.test','fixture-hash',TRUE,'原简介',7,4),(22,'favorite_other','other@example.test','fixture-hash',TRUE,'',0,0)");
        db.update("INSERT INTO restaurants(id,name,category_code) VALUES(1,'餐厅','OTHER')");
        db.update(
                "INSERT INTO restaurants(id,name,category_code,restaurant_type) VALUES(2,'食堂',NULL,'CANTEEN')");
        legacyBefore = db.queryForMap("SELECT * FROM users WHERE id=11");
        migrated = flyway().migrate().migrationsExecuted;
    }

    @BeforeEach
    void resetRelations() {
        db.update("DELETE FROM restaurant_favorites");
        db.update("UPDATE users SET favorites_public=FALSE WHERE id=22");
    }

    @AfterAll
    void cleanup() throws SQLException {
        if (!created) {
            return;
        }
        if (!database.matches("ginkgo_favorites_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test database");
        }
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void upgradePreservesAllLegacyUserFieldsAndDefaultsToPrivate() {
        assertThat(migrated).isEqualTo(1);
        var current = db.queryForMap("SELECT * FROM users WHERE id=11");
        assertThat(current.remove("favorites_public")).isIn(false, 0);
        assertThat(current).isEqualTo(legacyBefore);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM users WHERE favorites_public<>0", Long.class))
                .isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM restaurant_favorites", Long.class))
                .isZero();
    }

    @Test
    void uniquePerUserAndStoreAndSupportsCanteens() {
        add(11, 1);
        add(11, 2);
        add(22, 1);
        assertThatThrownBy(() -> add(11, 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM restaurant_favorites", Long.class))
                .isEqualTo(3);
        assertThat(db.queryForList("SELECT created_at FROM restaurant_favorites"))
                .allSatisfy(row -> assertThat(row.get("created_at")).isNotNull());
    }

    @Test
    void validatesIdsAndDoesNotCreateCrossModuleUserForeignKey() {
        for (String user : List.of("0", "-1", "9223372036854775808", "NULL")) {
            invalid(
                    () ->
                            db.update(
                                    "INSERT INTO restaurant_favorites(user_id,restaurant_id) VALUES("
                                            + user
                                            + ",1)"));
        }
        assertThatThrownBy(() -> add(11, 999)).isInstanceOf(DataIntegrityViolationException.class);
        add(999, 1); // 用户是跨模块逻辑ID，未来应用服务负责校验主体。
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name='restaurant_favorites'",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    void privacyRejectsNullAndNonBooleanValues() {
        for (String value : List.of("2", "-1", "NULL")) {
            invalid(() -> db.update("UPDATE users SET favorites_public=" + value + " WHERE id=22"));
        }
        db.update("UPDATE users SET favorites_public=TRUE WHERE id=22");
        assertThat(
                        db.queryForObject(
                                "SELECT favorites_public FROM users WHERE id=22", Boolean.class))
                .isTrue();
    }

    @Test
    void softDeleteAndRestoreKeepRelationsWhileHardDeleteIsRestricted() {
        add(11, 1);
        db.update(
                "UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),deleted_by=11,delete_reason_code='ADMIN_REMOVAL' WHERE id=1");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM restaurant_favorites WHERE restaurant_id=1",
                                Long.class))
                .isEqualTo(1);
        db.update(
                "UPDATE restaurants SET deleted_at=NULL,deleted_by=NULL,delete_reason_code=NULL WHERE id=1");
        assertThatThrownBy(() -> db.update("DELETE FROM restaurants WHERE id=1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        db.update("DELETE FROM restaurant_favorites WHERE user_id=11 AND restaurant_id=1");
        add(11, 1);
    }

    @Test
    void stableTimeIndexAndRepeatMigrationPreserveFavorites() {
        add(11, 1);
        add(11, 2);
        db.update("UPDATE restaurant_favorites SET created_at='2026-11-07 00:00:00'");
        var before = db.queryForList("SELECT * FROM restaurant_favorites ORDER BY id");
        assertThat(flyway().migrate().migrationsExecuted).isZero();
        assertThat(db.queryForList("SELECT * FROM restaurant_favorites ORDER BY id"))
                .isEqualTo(before);
        var ids =
                db.queryForList(
                        "SELECT id FROM restaurant_favorites WHERE user_id=11 ORDER BY created_at DESC,id DESC",
                        Long.class);
        assertThat(ids.getFirst()).isGreaterThan(ids.getLast());
        assertThat(
                        db.queryForList(
                                "SHOW INDEX FROM restaurant_favorites WHERE Key_name='idx_restaurant_favorites_user_time'"))
                .hasSize(3);
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

    private void add(long user, long restaurant) {
        db.update(
                "INSERT INTO restaurant_favorites(user_id,restaurant_id) VALUES(?,?)",
                user,
                restaurant);
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(url(database), username(), password())
                .locations("classpath:db/migration")
                .target("31")
                .load();
    }

    private String username() {
        return System.getenv().getOrDefault("FAVORITES_TEST_DB_USERNAME", "root");
    }

    private String password() {
        return Objects.requireNonNull(
                System.getenv("FAVORITES_TEST_DB_PASSWORD"), "Set isolated test password");
    }

    private String url(String name) {
        var server =
                System.getenv()
                        .getOrDefault("FAVORITES_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Loopback only");
        }
        return server + name + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), username(), password());
    }
}
