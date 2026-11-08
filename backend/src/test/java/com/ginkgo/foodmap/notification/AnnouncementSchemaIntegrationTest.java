package com.ginkgo.foodmap.notification;

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

/** V36真实MySQL结构验证，仅使用回环随机隔离库；不启动业务服务或访问开发数据。 */
@EnabledIfSystemProperty(named = "announcements.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnnouncementSchemaIntegrationTest {
    private String database;
    private boolean created;
    private JdbcTemplate db;
    private Map<String, Object> legacyUser;
    private Map<String, Object> legacyNotice;
    private int migrated;

    /** 从非空V35升级，原有用户与通知必须完整保留。 */
    @BeforeAll
    void initialize() throws SQLException {
        database = "ginkgo_announcement_it_" + UUID.randomUUID().toString().replace("-", "");
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
                .target("35")
                .load()
                .migrate();
        db.update(
                "INSERT INTO users(id,username,email,password_hash)"
                        + " VALUES(11,'announcement_old','old@example.test','fixture')");
        db.update(
                "INSERT INTO"
                    + " notification_messages(recipient_user_id,notification_type,source_id,actor_user_id)"
                    + " VALUES(11,'USER_FOLLOWED',1,22)");
        legacyUser = db.queryForMap("SELECT * FROM users WHERE id=11");
        legacyNotice =
                db.queryForMap("SELECT * FROM notification_messages WHERE recipient_user_id=11");
        migrated = flyway().migrate().migrationsExecuted;
    }

    @BeforeEach
    void resetAnnouncements() {
        db.update("DELETE FROM notification_announcement_user_states");
        db.update("DELETE FROM notification_announcements");
    }

    @Test
    void nonemptyUpgradePreservesExistingDataAndCreatesOnlyTwoEmptyTables() {
        assertThat(migrated).isEqualTo(1);
        assertThat(db.queryForMap("SELECT * FROM users WHERE id=11")).isEqualTo(legacyUser);
        assertThat(db.queryForMap("SELECT * FROM notification_messages WHERE recipient_user_id=11"))
                .isEqualTo(legacyNotice);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_announcements", Long.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM notification_announcement_user_states",
                                Long.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                        + " table_schema=DATABASE() AND"
                                        + " table_name<>'flyway_schema_history'",
                                Long.class))
                .isEqualTo(29);
    }

    @Test
    void publishRequestIsUniquePerPublisherAndRepeatMigrationPreservesData() {
        add(1, 11);
        assertThatThrownBy(() -> add(2, 11)).isInstanceOf(DataIntegrityViolationException.class);
        add(2, 22);
        var before = db.queryForList("SELECT * FROM notification_announcements ORDER BY id");
        assertThat(flyway().migrate().migrationsExecuted).isZero();
        assertThat(db.queryForList("SELECT * FROM notification_announcements ORDER BY id"))
                .usingRecursiveComparison()
                .isEqualTo(before);
    }

    @Test
    void titleContentAndPublisherHaveDatabaseBounds() {
        add(1, 11);
        for (String assignment :
                List.of(
                        "title=''",
                        "title='   '",
                        "content=''",
                        "content='   '",
                        "content=REPEAT('中',10001)",
                        "published_by=0",
                        "published_by=9223372036854775808")) {
            assertThatThrownBy(
                            () -> db.update("UPDATE notification_announcements SET " + assignment))
                    .isInstanceOf(DataAccessException.class);
        }
        db.update(
                "UPDATE notification_announcements SET"
                        + " title=REPEAT('中',120),content=REPEAT('中',10000)");
    }

    @Test
    void withdrawalRequiresCompleteConsistentMetadata() {
        add(1, 11);
        for (String assignment :
                List.of(
                        "status='DRAFT'",
                        "status='published'",
                        "status='WITHDRAWN'",
                        "withdrawn_by=11",
                        "withdrawn_at=CURRENT_TIMESTAMP(6)",
                        "status='WITHDRAWN',withdrawn_by=0,withdrawn_at=published_at",
                        "status='WITHDRAWN',withdrawn_by=11,withdrawn_at=published_at-INTERVAL 1"
                                + " SECOND")) {
            assertThatThrownBy(
                            () -> db.update("UPDATE notification_announcements SET " + assignment))
                    .isInstanceOf(DataAccessException.class);
        }
        db.update(
                "UPDATE notification_announcements SET"
                        + " status='WITHDRAWN',withdrawn_by=22,withdrawn_at=published_at");
        assertThat(db.queryForObject("SELECT status FROM notification_announcements", String.class))
                .isEqualTo("WITHDRAWN");
    }

    @Test
    void personalStateIsSparseUniqueAndCanDeleteWithoutReading() {
        add(1, 11);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM notification_announcement_user_states",
                                Long.class))
                .isZero();
        db.update(
                "INSERT INTO"
                    + " notification_announcement_user_states(user_id,announcement_id,deleted_at)"
                    + " VALUES(11,1,CURRENT_TIMESTAMP(6))");
        assertThat(
                        db.queryForMap(
                                        "SELECT read_at,deleted_at FROM"
                                                + " notification_announcement_user_states")
                                .get("read_at"))
                .isNull();
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " notification_announcement_user_states(user_id,announcement_id,read_at)"
                                            + " VALUES(11,1,CURRENT_TIMESTAMP(6))"))
                .isInstanceOf(DataIntegrityViolationException.class);
        db.update(
                "INSERT INTO notification_announcement_user_states(user_id,announcement_id,read_at)"
                        + " VALUES(22,1,CURRENT_TIMESTAMP(6))");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM notification_announcement_user_states",
                                Long.class))
                .isEqualTo(2);
    }

    @Test
    void stateRejectsEmptyActionBadIdsOrTimeAndProtectsAnnouncementReference() {
        add(1, 11);
        for (String values :
                List.of(
                        "11,1,NULL",
                        "0,1,CURRENT_TIMESTAMP(6)",
                        "9223372036854775808,1,CURRENT_TIMESTAMP(6)",
                        "11,999,CURRENT_TIMESTAMP(6)")) {
            assertThatThrownBy(
                            () ->
                                    db.update(
                                            "INSERT INTO"
                                                + " notification_announcement_user_states(user_id,announcement_id,read_at)"
                                                + " VALUES("
                                                    + values
                                                    + ")"))
                    .isInstanceOf(DataAccessException.class);
        }
        db.update(
                "INSERT INTO notification_announcement_user_states(user_id,announcement_id,read_at)"
                        + " VALUES(999,1,CURRENT_TIMESTAMP(6))");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE notification_announcement_user_states SET"
                                                + " read_at=created_at-INTERVAL 1 SECOND"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE notification_announcement_user_states SET"
                                                + " deleted_at=created_at-INTERVAL 1 SECOND"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.update("DELETE FROM notification_announcements WHERE id=1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("UPDATE notification_announcements SET id=2 WHERE id=1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void indexesAndForeignKeysStayInsideNotificationModule() {
        assertThat(
                        db.queryForList(
                                "SHOW INDEX FROM notification_announcements WHERE"
                                        + " Key_name='idx_announcement_status_time'"))
                .hasSize(3);
        assertThat(
                        db.queryForList(
                                "SHOW INDEX FROM notification_announcement_user_states WHERE"
                                        + " Key_name='PRIMARY'"))
                .hasSize(2);
        assertThat(
                        db.queryForList(
                                "SHOW INDEX FROM notification_announcement_user_states WHERE"
                                        + " Key_name='idx_announcement_state_announcement'"))
                .hasSize(1);
        assertThat(
                        db.queryForList(
                                "SELECT referenced_table_name FROM"
                                    + " information_schema.referential_constraints WHERE"
                                    + " constraint_schema=DATABASE() AND table_name IN"
                                    + " ('notification_announcements','notification_announcement_user_states')",
                                String.class))
                .containsExactly("notification_announcements");
    }

    private void add(long id, long publisher) {
        db.update(
                "INSERT INTO"
                    + " notification_announcements(id,client_request_id,request_hash,title,content,published_by)"
                    + " VALUES(?,UNHEX('00112233445566778899aabbccddeeff'),UNHEX(REPEAT('aa',32)),'校园公告','公告正文',?)",
                id,
                publisher);
    }

    @AfterAll
    void cleanup() throws SQLException {
        if (!created) {
            return;
        }
        if (!database.matches("ginkgo_announcement_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test database");
        }
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(url(database), username(), password())
                .locations("classpath:db/migration")
                .target("36")
                .load();
    }

    private String username() {
        return System.getenv().getOrDefault("ANNOUNCEMENT_TEST_DB_USERNAME", "root");
    }

    private String password() {
        return Objects.requireNonNull(
                System.getenv("ANNOUNCEMENT_TEST_DB_PASSWORD"), "Set isolated test password");
    }

    private String url(String name) {
        var server =
                System.getenv()
                        .getOrDefault(
                                "ANNOUNCEMENT_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Loopback only");
        }
        return server + name + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), username(), password());
    }
}
