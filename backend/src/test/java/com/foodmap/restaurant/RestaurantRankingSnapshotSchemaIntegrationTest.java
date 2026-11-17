package com.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * V43真实MySQL结构验证：显式开启后仅创建回环地址上的随机隔离库，绝不连接开发业务库。
 *
 * <p>覆盖全新迁移、非空V42升级、真实JSON及CHECK语义和事务回滚；不把这些结构测试当作榜单生成或缓存已经接通。
 */
@EnabledIfSystemProperty(named = "ranking.schema.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestaurantRankingSnapshotSchemaIntegrationTest {
    private static final String TABLE = "restaurant_ranking_snapshots";
    private static final List<String> COLUMNS =
            List.of(
                    "campus_code", "period_type", "window_start_at", "window_end_at",
                    "generated_at", "rules_snapshot", "items");
    private final Set<String> createdDatabases = new LinkedHashSet<>();
    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private String database;

    /** 显式启用却没有凭据时立即失败；所有DDL及测试写入均限定在本次创建的隔离库。 */
    @BeforeAll
    void initialize() throws SQLException {
        database = createDatabase();
        flyway(database, "43").migrate();
        source = new DriverManagerDataSource(url(database), username(), password());
        db = new JdbcTemplate(source);
    }

    @BeforeEach
    void resetSnapshots() {
        db.update("DELETE FROM " + TABLE);
    }

    /** 只清理本次成功创建且名称严格匹配的临时库，不使用Flyway clean。 */
    @AfterAll
    void cleanup() throws SQLException {
        for (String name : createdDatabases) {
            if (!name.matches("foodmap_ranking_schema_it_[a-f0-9]{32}")) {
                throw new IllegalStateException("Unexpected ranking test database name");
            }
            try (var connection = DriverManager.getConnection(url(""), username(), password());
                    var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE `" + name + "`");
            }
        }
    }

    @Test
    void freshMigrationCreatesAnEmptyTableAndRepeatedMigrationPreservesSnapshots() {
        var migration = flyway(database, "43");
        assertThat(migration.info().current().getVersion().getVersion()).isEqualTo("43");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class)).isZero();
        insert(values());
        var before = rows();
        assertThat(migration.migrate().migrationsExecuted).isZero();
        migration.validate();
        assertThat(rows()).isEqualTo(before);
    }

    /** 对V42全部应用表取真实校验和，验证V43只增加空表，不改写原有业务数据或字典。 */
    @Test
    void upgradeFromNonemptyV42PreservesEveryExistingApplicationTable() throws SQLException {
        String legacyDatabase = createDatabase();
        flyway(legacyDatabase, "42").migrate();
        var legacy =
                new JdbcTemplate(
                        new DriverManagerDataSource(url(legacyDatabase), username(), password()));
        legacy.update(
                "INSERT INTO users(id,username,email,password_hash)"
                        + " VALUES(11,'ranking_fixture','ranking@example.test','fixture-hash')");
        legacy.update("INSERT INTO restaurants(id,name,category_code) VALUES(1,'迁移前餐厅','OTHER')");
        var before = checksums(legacy);
        assertThat(flyway(legacyDatabase, "43").migrate().migrationsExecuted).isEqualTo(1);
        var after = checksums(legacy);
        assertThat(after.remove(TABLE)).isZero();
        assertThat(after).isEqualTo(before);
        assertThat(legacy.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class)).isZero();
        assertThat(flyway(legacyDatabase, "43").migrate().migrationsExecuted).isZero();
    }

    @Test
    void schemaHasSevenRequiredColumnsNativeJsonAndOnlyTheCompositePrimaryKey() {
        var columns =
                db.queryForList(
                        "SELECT COLUMN_NAME,IS_NULLABLE,COLUMN_DEFAULT,EXTRA FROM"
                                + " information_schema.columns WHERE table_schema=DATABASE()"
                                + " AND table_name=? ORDER BY ORDINAL_POSITION", TABLE);
        assertThat(columns.stream().map(row -> row.get("COLUMN_NAME")).toList())
                .containsExactlyElementsOf(COLUMNS);
        assertThat(columns).allSatisfy(row -> {
            assertThat(row.get("IS_NULLABLE")).isEqualTo("NO");
            assertThat(row.get("COLUMN_DEFAULT")).isNull();
            assertThat(row.get("EXTRA")).isEqualTo("");
        });
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"
                        + " AND table_name=? AND DATA_TYPE='json'", Long.class, TABLE)).isEqualTo(2);
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"
                        + " AND table_name=? AND DATA_TYPE='timestamp' AND DATETIME_PRECISION=6",
                Long.class, TABLE)).isEqualTo(3);
        assertThat(db.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.statistics WHERE table_schema=DATABASE()"
                        + " AND table_name=? ORDER BY SEQ_IN_INDEX", String.class, TABLE))
                .containsExactly("campus_code", "period_type");
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM information_schema.referential_constraints"
                        + " WHERE constraint_schema=DATABASE() AND table_name=?", Long.class, TABLE))
                .isZero();
        var table = db.queryForMap(
                "SELECT ENGINE,TABLE_COLLATION FROM information_schema.tables"
                        + " WHERE table_schema=DATABASE() AND table_name=?", TABLE);
        assertThat(table.get("ENGINE")).isEqualTo("InnoDB");
        assertThat(table.get("TABLE_COLLATION")).isEqualTo("utf8mb4_0900_ai_ci");
    }

    @Test
    void fourBoardKeysAreIndependentAndDuplicateKeysAreRejected() {
        insertAllBoards();
        reject(() -> insert(values()), 1062);
        assertThat(rows()).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "UNKNOWN", "qingshuihe", "QINGSHUIHE ", " SHAHE", "SHAHE\n"})
    void rejectsNoncanonicalCampusCodes(String code) {
        var values = values();
        values[0] = code;
        reject(() -> insert(values), 3819);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "DAY", "week", "WEEK ", " MONTH", "MONTH\t"})
    void rejectsNoncanonicalPeriodCodes(String code) {
        var values = values();
        values[1] = code;
        reject(() -> insert(values), 3819);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void rejectsSqlNullForEveryColumn(int index) {
        var values = values();
        values[index] = null;
        reject(() -> insert(values), 1048);
    }

    @ParameterizedTest
    @ValueSource(strings = {"campus_code", "period_type", "window_start_at", "window_end_at",
            "generated_at", "rules_snapshot", "items"})
    void omittedColumnsCannotCreateImplicitDefaults(String omitted) {
        var names = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        var values = values();
        for (int index = 0; index < COLUMNS.size(); index++) {
            if (!COLUMNS.get(index).equals(omitted)) {
                names.add(COLUMNS.get(index));
                parameters.add(values[index]);
            }
        }
        String sql = "INSERT INTO " + TABLE + "(" + String.join(",", names) + ") VALUES("
                + String.join(",", java.util.Collections.nCopies(names.size(), "?")) + ")";
        reject(() -> db.update(sql, parameters.toArray()), 1364);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "1", "\"text\"", "true"})
    void rulesMustBeAJsonObject(String json) {
        var values = values();
        values[5] = json;
        reject(() -> insert(values), 3819);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "1", "\"text\"", "true"})
    void itemsMustBeAJsonArray(String json) {
        var values = values();
        values[6] = json;
        reject(() -> insert(values), 3819);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 6})
    void nativeJsonRejectsMalformedDocuments(int index) {
        var values = values();
        values[index] = "{broken";
        reject(() -> insert(values), 3140);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 20})
    void acceptsSuccessfulEmptyAndFullBoards(int size) {
        var values = values();
        values[6] = items(size);
        insert(values);
        assertThat(db.queryForObject("SELECT JSON_LENGTH(items) FROM " + TABLE, Integer.class))
                .isEqualTo(size);
    }

    @Test
    void rejectsMoreThanTwentyItems() {
        var values = values();
        values[6] = items(21);
        reject(() -> insert(values), 3819);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rejectsEmptyReversedWindowsAndGenerationBeforeTheCutoff(int invalidCase) {
        var values = values();
        if (invalidCase == 0) {
            values[2] = values[3];
        } else if (invalidCase == 1) {
            values[2] = "2026-11-12 00:00:00.000000";
        } else {
            values[4] = "2026-11-11 11:59:59.999999";
        }
        reject(() -> insert(values), 3819);
    }

    @Test
    void preservesUnicodeStringIdsDecimalsAndMicrosecondTimestamps() {
        var values = values();
        values[4] = values[3]; // 生成时刻可以等于统计截止时刻。
        values[5] = "{\"scoreScale\":5,\"c\":3.5}";
        values[6] = "[{\"rank\":1,\"restaurantId\":\"9223372036854775807\","
                + "\"name\":\"校园餐厅🍜\",\"tasteScore\":4.625,\"cover\":null}]";
        insert(values);
        assertThat(db.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(items,'$[0].restaurantId')) FROM " + TABLE,
                String.class)).isEqualTo("9223372036854775807");
        assertThat(db.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(items,'$[0].name')) FROM " + TABLE,
                String.class)).isEqualTo("校园餐厅🍜");
        assertThat(db.queryForObject(
                "SELECT JSON_EXTRACT(items,'$[0].tasteScore') FROM " + TABLE, String.class))
                .isEqualTo("4.625");
        assertThat(db.queryForObject(
                "SELECT MICROSECOND(window_end_at) FROM " + TABLE, Integer.class)).isEqualTo(112233);
    }

    /** 验证整轮更新中途触发约束异常时，之前已更新的行及时间也一起回滚。 */
    @Test
    void failedPublicationRollsBackEveryUpdatedBoardAndSuccessfulRefreshKeepsFourRows() {
        insertAllBoards();
        var before = rows();
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThrows(DataAccessException.class, () -> transaction.executeWithoutResult(status -> {
            db.update("UPDATE " + TABLE + " SET generated_at='2026-11-11 13:00:00',items=?",
                    items(3));
            db.update("UPDATE " + TABLE + " SET rules_snapshot='[]' WHERE campus_code='SHAHE'");
        }));
        assertThat(rows()).isEqualTo(before);
        transaction.executeWithoutResult(status -> db.update(
                "UPDATE " + TABLE + " SET generated_at='2026-11-11 13:00:00',items=?", items(2)));
        assertThat(rows()).hasSize(4);
        assertThat(db.queryForList("SELECT JSON_LENGTH(items) FROM " + TABLE, Integer.class))
                .containsOnly(2);
    }

    private void insertAllBoards() {
        for (String campus : List.of("QINGSHUIHE", "SHAHE")) {
            for (String period : List.of("WEEK", "MONTH")) {
                var values = values();
                values[0] = campus;
                values[1] = period;
                values[2] = period.equals("MONTH")
                        ? "2026-10-12 12:00:00.112233" : values[2];
                insert(values);
            }
        }
    }

    private Object[] values() {
        return new Object[] {"QINGSHUIHE", "WEEK", "2026-11-04 12:00:00.112233",
                "2026-11-11 12:00:00.112233", "2026-11-11 12:00:30.112233", "{}", "[]"};
    }

    private void insert(Object[] values) {
        db.update("INSERT INTO " + TABLE + "(" + String.join(",", COLUMNS)
                + ") VALUES(?,?,?,?,?,?,?)", values);
    }

    private List<Map<String, Object>> rows() {
        return db.queryForList("SELECT * FROM " + TABLE + " ORDER BY campus_code,period_type");
    }

    private String items(int size) {
        return IntStream.rangeClosed(1, size)
                .mapToObj(rank -> "{\"rank\":" + rank + ",\"restaurantId\":\"" + rank + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** 核验实际MySQL错误码，防止把语法错误或连接错误误判成约束验证通过。 */
    private void reject(Runnable action, int code) {
        var exception = assertThrows(DataAccessException.class, action::run);
        assertThat(exception.getMostSpecificCause()).isInstanceOf(SQLException.class);
        assertThat(((SQLException) exception.getMostSpecificCause()).getErrorCode()).isEqualTo(code);
    }

    private Map<String, Long> checksums(JdbcTemplate template) {
        var result = new LinkedHashMap<String, Long>();
        for (String table : template.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()"
                        + " AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history'"
                        + " ORDER BY table_name", String.class)) {
            if (!table.matches("[a-z_]+")) {
                throw new IllegalStateException("Unexpected fixture table name");
            }
            var row = template.queryForMap("CHECKSUM TABLE `" + table + "` EXTENDED");
            result.put(table, ((Number) row.get("Checksum")).longValue());
        }
        return result;
    }

    private String createDatabase() throws SQLException {
        String name = "foodmap_ranking_schema_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), username(), password());
                var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + name
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            createdDatabases.add(name);
        }
        return name;
    }

    private Flyway flyway(String name, String target) {
        return Flyway.configure().dataSource(url(name), username(), password())
                .locations("classpath:db/migration").target(target).load();
    }

    private String url(String name) {
        var server = System.getenv().getOrDefault(
                "RANKING_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Ranking schema tests require loopback MySQL");
        }
        return server + name + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private String username() {
        return System.getenv().getOrDefault("RANKING_TEST_DB_USERNAME", "root");
    }

    private String password() {
        return Objects.requireNonNull(System.getenv("RANKING_TEST_DB_PASSWORD"),
                "Set RANKING_TEST_DB_PASSWORD for isolated schema tests");
    }
}
