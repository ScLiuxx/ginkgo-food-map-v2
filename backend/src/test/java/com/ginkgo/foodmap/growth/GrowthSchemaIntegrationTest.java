package com.ginkgo.foodmap.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.*;

/** 在隔离MySQL库执行V24，验证真实唯一键、CHECK、同模块外键及存量升级；不连接应用业务库。 */
@EnabledIfSystemProperty(named = "growth.integration", matches = "true")
class GrowthSchemaIntegrationTest {
    private static final String SERVER =
            System.getenv().getOrDefault("GROWTH_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
    private static final String USER =
            System.getenv().getOrDefault("GROWTH_TEST_DB_USERNAME", "root");
    private static final Set<String> CREATED = new LinkedHashSet<>();
    private static String database;
    private Connection connection;

    /** 显式开启却缺少连接配置时失败，不将环境未就绪记作通过。 */
    @BeforeAll
    static void prepare() throws SQLException {
        if (!SERVER.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException(
                    "Growth schema tests require a loopback MySQL server");
        }
        password();
        database = createDatabase();
        migration(database, "24").migrate();
    }

    /** 每例写入全部回滚，迁移本身在独立临时库验证。 */
    @BeforeEach
    void begin() throws SQLException {
        connection = connect(database);
        connection.setAutoCommit(false);
        execute(connection, "INSERT INTO growth_accounts(user_id) VALUES(10),(20)");
    }

    @AfterEach
    void rollback() throws SQLException {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    /** 只清理本次已成功创建、名称严格匹配的临时库，绝不执行Flyway clean。 */
    @AfterAll
    static void cleanup() throws SQLException {
        for (String name : CREATED) {
            if (!name.matches("ginkgo_growth_it_[a-f0-9]{32}")) {
                throw new IllegalStateException("Unexpected test database");
            }
            try (var c = connect("");
                    var st = c.createStatement()) {
                st.executeUpdate("DROP DATABASE `" + name + "`");
            }
        }
    }

    @Test
    void freshMigrationCreatesOnlyEmptyGrowthTablesAndCanRepeat() throws SQLException {
        try (var c = connect(database)) {
            assertThat(number(c, "SELECT COUNT(*) FROM growth_accounts")).isZero();
            assertThat(number(c, "SELECT COUNT(*) FROM growth_rewards")).isZero();
            assertThat(
                            number(
                                    c,
                                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'growth_%'"))
                    .isEqualTo(2);
            assertThat(
                            number(
                                    c,
                                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('growth_accounts','growth_rewards') AND column_name IN ('revision','rule_version','level')"))
                    .isZero();
            var flyway = migration(database, "24");
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            flyway.validate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("24");
        }
    }

    /** V23已有用户、商户、点评和回执在V24后保持原值，不自动创建账户或历史奖励。 */
    @Test
    void upgradeV23PreservesBusinessDataAndDoesNotBackfill() throws SQLException {
        String name = createDatabase();
        migration(name, "23").migrate();
        try (var c = connect(name)) {
            seedContent(c);
            execute(
                    c,
                    "INSERT INTO review_request_receipts(author_user_id,client_request_id,review_id,restaurant_id) SELECT author_user_id,client_request_id,id,restaurant_id FROM reviews");
            var users =
                    strings(
                            c,
                            "SELECT CONCAT(id,':',username,':',credential_version) FROM users ORDER BY id");
            var shops =
                    strings(
                            c,
                            "SELECT CONCAT(id,':',name,':',search_projection_version,':',updated_at) FROM restaurants ORDER BY id");
            var reviews =
                    strings(
                            c,
                            "SELECT CONCAT(id,':',author_user_id,':',body,':',overall_score,':',HEX(client_request_id)) FROM reviews ORDER BY id");
            var receipts =
                    strings(
                            c,
                            "SELECT CONCAT(author_user_id,':',review_id,':',HEX(client_request_id)) FROM review_request_receipts ORDER BY review_id");
            var flyway = migration(name, "24");
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(
                            strings(
                                    c,
                                    "SELECT CONCAT(id,':',username,':',credential_version) FROM users ORDER BY id"))
                    .isEqualTo(users);
            assertThat(
                            strings(
                                    c,
                                    "SELECT CONCAT(id,':',name,':',search_projection_version,':',updated_at) FROM restaurants ORDER BY id"))
                    .isEqualTo(shops);
            assertThat(
                            strings(
                                    c,
                                    "SELECT CONCAT(id,':',author_user_id,':',body,':',overall_score,':',HEX(client_request_id)) FROM reviews ORDER BY id"))
                    .isEqualTo(reviews);
            assertThat(
                            strings(
                                    c,
                                    "SELECT CONCAT(author_user_id,':',review_id,':',HEX(client_request_id)) FROM review_request_receipts ORDER BY review_id"))
                    .isEqualTo(receipts);
            assertThat(number(c, "SELECT COUNT(*) FROM growth_accounts")).isZero();
            assertThat(number(c, "SELECT COUNT(*) FROM growth_rewards")).isZero();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            flyway.validate();
        }
    }

    @Test
    void businessKeySurvivesRevocationAndDistinguishesUsersAndRewardTypes() throws SQLException {
        reward(connection, 10, "FIRST_STORE_REVIEW", "42", "REVIEW", 1001);
        reject(
                1062,
                "INSERT INTO growth_rewards(user_id,reward_type,business_key,source_type,source_id,amount) VALUES(10,'FIRST_STORE_REVIEW','42','REVIEW',1002,5)");
        execute(
                connection,
                "UPDATE growth_rewards SET status='REVOKED',revoked_at=granted_at,disposition_id=7001");
        reject(
                1062,
                "INSERT INTO growth_rewards(user_id,reward_type,business_key,source_type,source_id,amount) VALUES(10,'FIRST_STORE_REVIEW','42','REVIEW',1003,5)");
        reward(connection, 20, "FIRST_STORE_REVIEW", "42", "REVIEW", 1004);
        reward(connection, 10, "CORRECTION_APPROVED", "42", "RESTAURANT_REQUEST", 42);
        assertThat(number(connection, "SELECT COUNT(*) FROM growth_rewards")).isEqualTo(3);
    }

    @Test
    void keysCompareCaseExactlyAndRespectLengthLimit() throws SQLException {
        reward(connection, 10, "REVIEW_LIKED", "review:1:liker:A", "REVIEW", 1);
        reward(connection, 10, "REVIEW_LIKED", "review:1:liker:a", "REVIEW", 1);
        reward(connection, 10, "FIRST_STORE_REVIEW", "A".repeat(96), "REVIEW", 2);
        assertThrows(
                SQLException.class,
                () -> reward(connection, 10, "FIRST_STORE_REVIEW", "A".repeat(97), "REVIEW", 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "42 ", " 42", "a\tb", "a/b", "a.b", "a\nb"})
    void rejectsEmptyOrAmbiguousKeys(String key) {
        assertThrows(
                SQLException.class,
                () -> reward(connection, 10, "FIRST_STORE_REVIEW", key, "REVIEW", 1));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "user_id=0",
                "user_id=9223372036854775808",
                "source_id=0",
                "source_id=9223372036854775808",
                "amount=0",
                "amount=-1",
                "reward_type='UNKNOWN'",
                "reward_type='first_store_review'",
                "reward_type='FIRST_STORE_REVIEW '",
                "source_type='review'",
                "source_type='REVIEW '",
                "source_type='UNKNOWN'",
                "source_type='RESTAURANT_REQUEST'",
                "status='UNKNOWN'",
                "status='granted'",
                "status='GRANTED '"
            })
    void rejectsInvalidRewardValues(String assignment) throws SQLException {
        reward(connection, 10, "FIRST_STORE_REVIEW", "42", "REVIEW", 1);
        assertThrows(
                SQLException.class,
                () -> execute(connection, "UPDATE growth_rewards SET " + assignment));
    }

    @Test
    void revocationRequiresConsistentMetadataAndKeepsOriginalGrant() throws SQLException {
        reward(connection, 10, "FIRST_STORE_REVIEW", "42", "REVIEW", 1);
        for (String assignment :
                List.of(
                        "revoked_at=granted_at",
                        "disposition_id=7",
                        "status='REVOKED'",
                        "status='REVOKED',revoked_at=granted_at",
                        "status='REVOKED',disposition_id=7",
                        "status='REVOKED',revoked_at=granted_at,disposition_id=0",
                        "status='REVOKED',revoked_at=DATE_SUB(granted_at,INTERVAL 1 SECOND),disposition_id=7")) {
            reject(3819, "UPDATE growth_rewards SET " + assignment);
        }
        execute(
                connection,
                "UPDATE growth_rewards SET status='REVOKED',revoked_at=granted_at,disposition_id=7");
        assertThat(
                        strings(
                                connection,
                                "SELECT CONCAT(amount,':',source_id,':',status,':',disposition_id) FROM growth_rewards"))
                .containsExactly("5:1:REVOKED:7");
    }

    @Test
    void accountReferencesStayInsideGrowthAndDeletedContentDoesNotRemoveRewards()
            throws SQLException {
        seedContent(connection);
        reward(connection, 10, "FIRST_STORE_REVIEW", "42", "REVIEW", 1001);
        execute(connection, "DELETE FROM reviews WHERE id=1001");
        execute(connection, "DELETE FROM restaurants WHERE id=42");
        execute(connection, "DELETE FROM users WHERE id=10");
        assertThat(
                        number(
                                connection,
                                "SELECT COUNT(*) FROM growth_rewards WHERE source_id=1001 AND status='GRANTED'"))
                .isEqualTo(1);
        reject(1451, "DELETE FROM growth_accounts WHERE user_id=10");
        reject(
                1452,
                "INSERT INTO growth_rewards(user_id,reward_type,business_key,source_type,source_id,amount) VALUES(999,'FIRST_STORE_REVIEW','42','REVIEW',1001,5)");
        assertThat(
                        strings(
                                connection,
                                "SELECT referenced_table_name FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name IN ('growth_accounts','growth_rewards') AND referenced_table_name IS NOT NULL"))
                .containsExactly("growth_accounts");
    }

    @Test
    void accountValidatesIdentityAndExperienceWithoutCreatingVersions() throws SQLException {
        assertThat(number(connection, "SELECT experience FROM growth_accounts WHERE user_id=10"))
                .isZero();
        reject(3819, "UPDATE growth_accounts SET experience=-1 WHERE user_id=10");
        reject(3819, "INSERT INTO growth_accounts(user_id) VALUES(0)");
        reject(3819, "INSERT INTO growth_accounts(user_id) VALUES(9223372036854775808)");
        reject(1048, "UPDATE growth_accounts SET experience=NULL WHERE user_id=10");
        execute(
                connection,
                "UPDATE growth_accounts SET experience=9223372036854775807 WHERE user_id=10");
        assertThat(number(connection, "SELECT experience FROM growth_accounts WHERE user_id=10"))
                .isEqualTo(Long.MAX_VALUE);
    }

    /** 只写入当前测试库的最小内容，原用户与源内容没有跨模块成长外键。 */
    private static void seedContent(Connection c) throws SQLException {
        execute(
                c,
                "INSERT INTO users(id,username,email,password_hash) VALUES(10,'growth_user','growth@example.invalid','test-only')");
        execute(c, "INSERT INTO restaurants(id,name,category_code) VALUES(42,'迁移验证店','OTHER')");
        execute(
                c,
                "INSERT INTO reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash) VALUES(1001,42,10,8,8,8,8,1,'迁移保留点评正文',UUID_TO_BIN(UUID()),UNHEX(REPEAT('11',32)))");
    }

    private void reject(int errorCode, String sql) {
        assertThat(assertThrows(SQLException.class, () -> execute(connection, sql)).getErrorCode())
                .isEqualTo(errorCode);
    }

    private static void reward(
            Connection c, long user, String type, String key, String source, long sourceId)
            throws SQLException {
        execute(
                c,
                "INSERT INTO growth_rewards(user_id,reward_type,business_key,source_type,source_id,amount) VALUES(?,?,?,?,?,5)",
                user,
                type,
                key,
                source,
                sourceId);
    }

    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (var st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setObject(i + 1, args[i]);
            }
            st.executeUpdate();
        }
    }

    private static long number(Connection c, String sql) throws SQLException {
        return Long.parseLong(strings(c, sql).getFirst());
    }

    private static List<String> strings(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement();
                var rows = st.executeQuery(sql)) {
            var values = new ArrayList<String>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private static String createDatabase() throws SQLException {
        String name = "ginkgo_growth_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = connect("");
                var st = c.createStatement()) {
            st.executeUpdate(
                    "CREATE DATABASE `"
                            + name
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        CREATED.add(name);
        return name;
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), USER, password());
    }

    private static String url(String name) {
        return SERVER
                + name
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true&sessionVariables=sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'";
    }

    private static String password() {
        String value = System.getenv("GROWTH_TEST_DB_PASSWORD");
        if (value == null) {
            throw new IllegalStateException(
                    "Set GROWTH_TEST_DB_PASSWORD for the isolated test server");
        }
        return value;
    }

    private static Flyway migration(String name, String target) {
        return Flyway.configure()
                .dataSource(url(name), USER, password())
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .target(MigrationVersion.fromVersion(target))
                .load();
    }
}
