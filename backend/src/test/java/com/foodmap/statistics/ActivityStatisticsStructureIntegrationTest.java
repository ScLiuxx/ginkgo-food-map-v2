package com.foodmap.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** 显式开启后验证V49与Redis资源，只访问随机隔离库及测试键，不启动应用或连接业务库。 */
@EnabledIfSystemProperty(named = "activity.structure.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActivityStatisticsStructureIntegrationTest {
    private static final String TABLE = "activity_daily_summaries";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Set<String> databases = new LinkedHashSet<>();
    private final List<String> redisKeys = new ArrayList<>();
    private final DefaultRedisScript<Long> record = new DefaultRedisScript<>();
    private String server;
    private String username;
    private String password;
    private String database;
    private JdbcTemplate db;
    private Map<String, Long> beforeUpgrade;
    private int upgraded;
    private LettuceConnectionFactory redisFactory;
    private StringRedisTemplate redis;

    @BeforeAll
    void initialize() throws SQLException {
        record.setLocation(new ClassPathResource("redis/activity-record-user.lua"));
        record.setResultType(Long.class);
        server = required("ACTIVITY_TEST_DB_SERVER");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException(
                    "Activity tests require an explicit loopback server");
        }
        username = required("ACTIVITY_TEST_DB_USERNAME");
        password = required("ACTIVITY_TEST_DB_PASSWORD");
        int redisPort = Integer.parseInt(required("ACTIVITY_TEST_REDIS_PORT"));
        if (redisPort <= 0 || redisPort > 65535 || redisPort == 6379) {
            throw new IllegalArgumentException("Use an explicitly mapped isolated Redis port");
        }
        database = createDatabase();
        flyway(database, "48").migrate();
        db = jdbc(database);
        db.update(
                "INSERT INTO users(username,email,password_hash)"
                        + " VALUES('activity_fixture','activity@example.test','fixture-hash')");
        db.update("INSERT INTO restaurants(name,category_code) VALUES('迁移保留店','OTHER')");
        beforeUpgrade = checksums(db);
        upgraded = flyway(database, "49").migrate().migrationsExecuted;

        var config = new RedisStandaloneConfiguration("127.0.0.1", redisPort);
        redisFactory = new LettuceConnectionFactory(config);
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        System.out.println(
                "Activity structure test MySQL version: "
                        + db.queryForObject("SELECT VERSION()", String.class));
    }

    @BeforeEach
    void reset() {
        db.update("DELETE FROM " + TABLE);
        if (!redisKeys.isEmpty()) {
            redis.delete(redisKeys);
            redisKeys.clear();
        }
    }

    /** 只删除本例成功创建的随机库和键，不使用Flyway clean、FLUSHDB或业务库配置。 */
    @AfterAll
    void cleanup() throws SQLException {
        try {
            if (redis != null && !redisKeys.isEmpty()) {
                redis.delete(redisKeys);
            }
        } finally {
            if (redisFactory != null) {
                redisFactory.destroy();
            }
            for (String name : databases) {
                if (!name.matches("foodmap_activity_it_[a-f0-9]{32}")) {
                    throw new IllegalStateException("Unexpected activity test database");
                }
                try (var connection = DriverManager.getConnection(url(""), username, password);
                        var statement = connection.createStatement()) {
                    statement.executeUpdate("DROP DATABASE `" + name + "`");
                }
            }
        }
    }

    @Test
    void upgradeOnlyAddsAnEmptyTableAndDoesNotEnableCollection() {
        assertThat(upgraded).isEqualTo(1);
        var after = checksums(db);
        assertThat(after.remove(TABLE)).isZero();
        assertThat(after).isEqualTo(beforeUpgrade);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM app_metadata"
                                        + " WHERE metadata_key='activity_stats_started_at'",
                                Long.class))
                .isZero();
        assertThat(flyway(database, "49").migrate().migrationsExecuted).isZero();
        flyway(database, "49").validate();
    }

    @Test
    void freshMigrationCreatesThirtyFiveApplicationTablesWithoutBackfill() throws SQLException {
        String fresh = createDatabase();
        assertThat(flyway(fresh, "49").migrate().migrationsExecuted).isEqualTo(49);
        var freshDb = jdbc(fresh);
        assertThat(
                        freshDb.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                        + " table_schema=DATABASE() AND"
                                        + " table_name<>'flyway_schema_history'",
                                Long.class))
                .isEqualTo(35);
        assertThat(freshDb.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class)).isZero();
        assertThat(
                        freshDb.queryForObject(
                                "SELECT COUNT(*) FROM app_metadata"
                                        + " WHERE metadata_key='activity_stats_started_at'",
                                Long.class))
                .isZero();
    }

    @Test
    void dateIsTheOnlyKeyAndUnknownCountsAreDifferentFromZero() {
        db.update("INSERT INTO " + TABLE + "(stat_date) VALUES('2026-11-20')");
        var row = db.queryForMap("SELECT * FROM " + TABLE);
        assertThat(row.keySet())
                .containsExactlyInAnyOrder(
                        "stat_date",
                        "active_user_count",
                        "new_user_count",
                        "published_review_count",
                        "collection_status",
                        "active_synced_at");
        assertThat(row.get("collection_status")).isEqualTo("UNKNOWN");
        for (String column :
                List.of(
                        "active_user_count",
                        "new_user_count",
                        "published_review_count",
                        "active_synced_at")) {
            assertThat(row.get(column)).isNull();
        }
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO "
                                                + TABLE
                                                + "(stat_date) VALUES('2026-11-20')"))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        db.queryForList(
                                "SELECT COLUMN_NAME FROM information_schema.statistics"
                                        + " WHERE table_schema=DATABASE() AND table_name=?",
                                String.class,
                                TABLE))
                .containsExactly("stat_date");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.referential_constraints"
                                        + " WHERE constraint_schema=DATABASE() AND table_name=?",
                                Long.class,
                                TABLE))
                .isZero();
        db.update(
                "UPDATE "
                        + TABLE
                        + " SET active_user_count=0,new_user_count=0,"
                        + "published_review_count=0,active_synced_at=CURRENT_TIMESTAMP(6),collection_status='NORMAL'");
        assertThat(db.queryForObject("SELECT active_user_count FROM " + TABLE, Long.class))
                .isZero();
    }

    @Test
    void rejectsInvalidCountsStatusesAndIncompleteNormalRows() {
        db.update("INSERT INTO " + TABLE + "(stat_date) VALUES('2026-11-20')");
        for (String column :
                List.of("active_user_count", "new_user_count", "published_review_count")) {
            for (String value : List.of("-1", "9223372036854775808")) {
                assertThatThrownBy(
                                () -> db.update("UPDATE " + TABLE + " SET " + column + "=" + value))
                        .isInstanceOf(DataAccessException.class);
            }
        }
        for (String status : List.of("normal", "NORMAL ", "BAD")) {
            assertThatThrownBy(
                            () -> db.update("UPDATE " + TABLE + " SET collection_status=?", status))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> db.update("UPDATE " + TABLE + " SET collection_status='NORMAL'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.update("UPDATE " + TABLE + " SET active_user_count=0"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE "
                                                + TABLE
                                                + " SET active_synced_at=CURRENT_TIMESTAMP(6)"))
                .isInstanceOf(DataAccessException.class);
        db.update(
                "UPDATE "
                        + TABLE
                        + " SET collection_status='PARTIAL',new_user_count=9223372036854775807");
    }

    @Test
    void repeatedVisitsKeepOneMemberAndTheSameAbsoluteExpiryIncludingLargeIds() {
        String key = key();
        String expiry = expiry();
        assertThat(record(key, "9223372036854775807", expiry)).isEqualTo(1);
        long firstExpiry = expireTime(key);
        assertThat(record(key, "9223372036854775807", expiry)).isZero();
        assertThat(record(key, "9223372036854775806", expiry)).isEqualTo(1);
        assertThat(redis.opsForSet().members(key))
                .containsExactlyInAnyOrder("9223372036854775807", "9223372036854775806");
        assertThat(expireTime(key)).isEqualTo(firstExpiry).isEqualTo(Long.parseLong(expiry));
    }

    @Test
    void concurrentVisitsCountAnAccountOnceAndSevenDayUnionIsNotTheSum() throws Exception {
        String today = key();
        String yesterday = key();
        String expiry = expiry();
        var start = new CountDownLatch(1);
        long additions = 0;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 12; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return record(today, "11", expiry);
                                }));
            }
            start.countDown();
            for (var future : futures) {
                additions += future.get();
            }
        }
        assertThat(additions).isEqualTo(1);
        record(today, "22", expiry);
        record(yesterday, "11", expiry);
        record(yesterday, "33", expiry);
        assertThat(redis.opsForSet().size(today)).isEqualTo(2);
        assertThat(redis.opsForSet().size(yesterday)).isEqualTo(2);
        var week = new ArrayList<String>();
        week.add(yesterday);
        for (int i = 0; i < 5; i++) {
            week.add(key());
        }
        assertThat(redis.opsForSet().union(today, week))
                .containsExactlyInAnyOrder("11", "22", "33");
    }

    @Test
    void expiredOrMalformedArgumentsDoNotCreateKeysOrPermanentSets() {
        String key = key();
        assertThat(
                        record(
                                key,
                                "11",
                                String.valueOf(Instant.now().minusSeconds(1).getEpochSecond())))
                .isEqualTo(-1);
        assertThat(redis.hasKey(key)).isFalse();
        for (String user : List.of("0", "-1", "01", "9223372036854775808", "1e2")) {
            assertThatThrownBy(() -> record(key, user, expiry()))
                    .isInstanceOf(DataAccessException.class);
        }
        for (String invalidExpiry : List.of("0", "-1", "1e10", "1.5", "253402300800", "garbage")) {
            assertThatThrownBy(() -> record(key, "11", invalidExpiry))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThat(redis.hasKey(key)).isFalse();
    }

    private Long record(String key, String user, String expiry) {
        return redis.execute(record, List.of(key), user, expiry);
    }

    private long expireTime(String key) {
        return redis.execute(
                new DefaultRedisScript<>("return redis.call('EXPIRETIME', KEYS[1])", Long.class),
                List.of(key));
    }

    private String key() {
        String key = "foodmap:activity:it:" + UUID.randomUUID();
        redisKeys.add(key);
        return key;
    }

    private String expiry() {
        return String.valueOf(LocalDate.now(ZONE).plusDays(90).atStartOfDay(ZONE).toEpochSecond());
    }

    private String createDatabase() throws SQLException {
        String name = "foodmap_activity_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), username, password);
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE DATABASE `"
                            + name
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            databases.add(name);
        }
        return name;
    }

    private Flyway flyway(String name, String target) {
        return Flyway.configure()
                .dataSource(url(name), username, password)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private JdbcTemplate jdbc(String name) {
        return new JdbcTemplate(new DriverManagerDataSource(url(name), username, password));
    }

    private String url(String name) {
        return server
                + name
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    }

    private Map<String, Long> checksums(JdbcTemplate template) {
        var result = new LinkedHashMap<String, Long>();
        for (String table :
                template.queryForList(
                        "SELECT table_name FROM information_schema.tables WHERE"
                            + " table_schema=DATABASE() AND table_name<>'flyway_schema_history'",
                        String.class)) {
            result.put(
                    table,
                    ((Number)
                                    template.queryForMap("CHECKSUM TABLE `" + table + "`")
                                            .get("Checksum"))
                            .longValue());
        }
        return result;
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing isolated-test configuration: " + name);
        }
        return value;
    }
}
