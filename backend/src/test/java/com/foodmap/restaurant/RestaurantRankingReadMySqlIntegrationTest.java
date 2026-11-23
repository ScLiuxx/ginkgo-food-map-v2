package com.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import com.foodmap.restaurant.service.RestaurantRankingReadService;
import com.foodmap.restaurant.cache.RestaurantRankingCache;
import com.foodmap.restaurant.cache.RestaurantRankingLocalCache;
import com.foodmap.restaurant.config.RestaurantRankingProperties;
import com.foodmap.restaurant.entity.RestaurantRankingSnapshotEntity;
import com.foodmap.restaurant.mapper.RestaurantRankingSnapshotMapper;
import com.foodmap.restaurant.service.RestaurantRankingSnapshotPublishService;
import com.foodmap.restaurant.support.RestaurantRankingKey;
import com.foodmap.restaurant.support.RestaurantRankingSnapshotCodec;
import com.foodmap.security.TestJwtKeys;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实MySQL、MyBatis、连接池、安全过滤链及HTTP读取验证；显式启用才运行。
 * 仅创建回环地址上的随机隔离库，夹具为四份合成榜单，不读写开发业务库或本地密钥。
 */
@EnabledIfSystemProperty(named = "ranking.read.integration", matches = "true")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.minimum-idle=2", "spring.datasource.hikari.maximum-pool-size=10",
        "app.search.elasticsearch.enabled=false", "app.search.sync.enabled=false",
        "app.search.query.enabled=false", "app.file.oss.enabled=false"
})
@Import({TestJwtKeys.class, RestaurantRankingReadMySqlIntegrationTest.ProbeConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RestaurantRankingReadMySqlIntegrationTest {
    private static final String[][] KEYS = {
            {"QINGSHUIHE", "WEEK"}, {"QINGSHUIHE", "MONTH"},
            {"SHAHE", "WEEK"}, {"SHAHE", "MONTH"}
    };
    private static String database;
    private static boolean created;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RestaurantRankingReadService service;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private SnapshotSqlProbe probe;
    @Autowired private RestaurantRankingProperties ranking;
    @Autowired private RestaurantRankingCache cache;
    @Autowired private RestaurantRankingLocalCache local;
    @Autowired private RestaurantRankingSnapshotPublishService publisher;
    @Autowired private RestaurantRankingSnapshotMapper snapshots;
    @Autowired private RestaurantRankingSnapshotCodec codec;
    @Autowired private StringRedisTemplate redis;
    private Instant cutoff;

    /** URL只能由回环服务器加本次随机库名组成；不能通过环境变量指定业务库。 */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) throws SQLException {
        database = "foodmap_ranking_read_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect(""); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + database
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", RestaurantRankingReadMySqlIntegrationTest::username);
        registry.add("spring.datasource.password", RestaurantRankingReadMySqlIntegrationTest::password);
        if (Boolean.getBoolean("ranking.redis.integration")) {
            int port = Integer.parseInt(System.getenv().getOrDefault("RANKING_TEST_REDIS_PORT", "6379"));
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid loopback Redis test port");
            }
            registry.add("spring.data.redis.host", () -> "127.0.0.1");
            registry.add("spring.data.redis.port", () -> port);
            registry.add("spring.data.redis.database", () -> 0);
            registry.add("spring.data.redis.password", () -> System.getenv().getOrDefault("RANKING_TEST_REDIS_PASSWORD", ""));
            registry.add("app.ranking.redis-enabled", () -> true);
            registry.add("app.ranking.redis-key-prefix", () -> "foodmap:test:" + database + ":");
        }
    }

    /** 每份20项，使用大整数ID、中文名称及稳定图片路径；不为夹具创建真实店铺或图片。 */
    @BeforeEach
    void fixtures() {
        ranking.setCaffeineEnabled(false);
        local.invalidate(RestaurantRankingKey.ALL);
        ranking.setRedisEnabled(false);
        if (Boolean.getBoolean("ranking.redis.integration")) {
            redis.delete(RestaurantRankingKey.ALL.stream().map(cache::redisKey).toList());
        }
        probe.beforeWrite = null;
        probe.failOnWrite = 0;
        probe.writes.set(0);
        jdbc.update("DELETE FROM restaurant_ranking_snapshots");
        cutoff = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(120);
        for (String[] key : KEYS) {
            jdbc.update("INSERT INTO restaurant_ranking_snapshots"
                            + "(campus_code,period_type,window_start_at,window_end_at,generated_at,rules_snapshot,items)"
                            + " VALUES(?,?,?,?,?,?,?)", key[0], key[1],
                    java.sql.Timestamp.from(cutoff.minus(Duration.ofHours(key[1].equals("WEEK") ? 168 : 720))),
                    java.sql.Timestamp.from(cutoff), java.sql.Timestamp.from(cutoff.plusSeconds(30)),
                    rules(), items(key[0], key[1]));
        }
        probe.selects.set(0);
    }

    /** 只删除这个随机前缀的四个确切键，不影响其他测试或开发会话。 */
    @AfterEach
    void clearRedisFixtures() {
        local.invalidate(RestaurantRankingKey.ALL);
        if (Boolean.getBoolean("ranking.redis.integration")) {
            redis.delete(RestaurantRankingKey.ALL.stream().map(cache::redisKey).toList());
        }
    }

    /** 只清理本次成功创建、名称严格匹配的测试库，不调用Flyway clean。 */
    @AfterAll
    static void cleanup() throws SQLException {
        if (!created) {
            return;
        }
        if (!database.matches("foodmap_ranking_read_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected ranking read database name");
        }
        try (var connection = connect(""); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void anonymousHttpReadsEveryCombinationWithOnlyOneSelectAndNoInternalFields() throws Exception {
        try (var client = client()) {
            for (int i = 0; i < KEYS.length; i++) {
                var response = client.send(request(i), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertReady(response, i);
                var data = json.readTree(response.body()).get("data");
                assertThat(data.get("windowEndAt").stringValue()).isEqualTo(cutoff.toString());
                assertThat(data.get("scoreScale").intValue()).isEqualTo(5);
                assertThat(data.get("items").get(0).get("tasteScore").decimalValue())
                        .isEqualByComparingTo("4.5");
                assertThat(response.body()).doesNotContain("sortingScore", "rulesSnapshot", "fixtureOnly");
                assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            }
            assertThat(probe.selects.get()).isEqualTo(4);
        }
    }

    @Test
    void missingSuccessfulEmptyAndExpiredSnapshotsRemainDifferent() throws Exception {
        jdbc.update("DELETE FROM restaurant_ranking_snapshots WHERE campus_code='QINGSHUIHE' AND period_type='WEEK'");
        jdbc.update("UPDATE restaurant_ranking_snapshots SET items='[]' WHERE campus_code='QINGSHUIHE' AND period_type='MONTH'");
        jdbc.update("UPDATE restaurant_ranking_snapshots SET window_start_at=window_start_at-INTERVAL 25 HOUR,"
                + "window_end_at=window_end_at-INTERVAL 25 HOUR WHERE campus_code='SHAHE' AND period_type='WEEK'");
        try (var client = client()) {
            String[] statuses = {"PREPARING", "READY", "UNAVAILABLE"};
            for (int i = 0; i < 3; i++) {
                var response = client.send(request(i), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var data = json.readTree(response.body()).get("data");
                assertThat(data.get("status").stringValue()).isEqualTo(statuses[i]);
                assertThat(data.get("items").size()).isZero();
            }
        }
    }

    @Test
    void invalidOrMissingQueriesDoNotReachMysql() throws Exception {
        try (var client = client()) {
            for (String query : List.of("", "campusCode=QINGSHUIHE", "campusCode=OTHER&periodType=WEEK",
                    "campusCode=SHAHE&periodType=week", "campusCode=SHAHE&periodType=DAY")) {
                var request = HttpRequest.newBuilder(URI.create(baseUrl() + "?" + query)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(400);
                assertThat(json.readTree(response.body()).get("code").stringValue())
                        .isEqualTo("COMMON_VALIDATION_FAILED");
            }
        }
        assertThat(probe.selects.get()).isZero();
    }

    @Test
    void publicGetDoesNotOpenWriteMethodsOrNestedPaths() throws Exception {
        try (var client = client()) {
            var post = HttpRequest.newBuilder(URI.create(baseUrl())).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertThat(client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            var nested = HttpRequest.newBuilder(URI.create(baseUrl() + "/rebuild")).GET().build();
            assertThat(client.send(nested, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        assertThat(probe.selects.get()).isZero();
    }

    @Test
    void corruptInternalFormatReturnsSafe503InsteadOfEmptySuccess() throws Exception {
        jdbc.update("UPDATE restaurant_ranking_snapshots SET rules_snapshot='{}' WHERE campus_code='QINGSHUIHE'");
        try (var client = client()) {
            var response = client.send(request(0), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(json.readTree(response.body()).get("code").stringValue())
                    .isEqualTo("RESTAURANT_RANKING_UNAVAILABLE");
            assertThat(response.body()).doesNotContain("rules_snapshot", "SELECT", "IllegalArgumentException");
        }
    }

    @Test
    void repeatedReadsSeeCommittedUpdatesWithoutHiddenCaching() {
        for (int i = 0; i < 8; i++) {
            assertThat(service.read("QINGSHUIHE", "WEEK").getItems()).hasSize(20);
        }
        jdbc.update("UPDATE restaurant_ranking_snapshots SET items='[]' WHERE campus_code='QINGSHUIHE' AND period_type='WEEK'");
        assertThat(service.read("QINGSHUIHE", "WEEK").getItems()).isEmpty();
        assertThat(probe.selects.get()).isEqualTo(9);
    }

    @Test
    @EnabledIfSystemProperty(named = "ranking.redis.integration", matches = "true")
    void redisColdLoadThenHotReadHaveIdenticalPublicResponsesAndNormalTtl() {
        var mysql = service.read("QINGSHUIHE", "WEEK");
        probe.selects.set(0);
        ranking.setRedisEnabled(true);
        var cold = service.read("QINGSHUIHE", "WEEK");
        long before = redis.getExpire(redisKey(0), TimeUnit.MILLISECONDS);
        for (int i = 0; i < 10; i++) {
            assertThat(service.read("QINGSHUIHE", "WEEK")).isEqualTo(mysql);
        }
        assertThat(cold).isEqualTo(mysql);
        assertThat(probe.selects.get()).isEqualTo(1);
        assertThat(before).isBetween(7_190_000L, 7_200_000L);
        assertThat(redis.getExpire(redisKey(0), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(before);
        var payload = json.readTree(redis.opsForValue().get(redisKey(0)));
        assertThat(payload.get("rulesSnapshot").isObject()).isTrue();
        assertThat(payload.get("items").isArray()).isTrue();
    }

    @Test
    @EnabledIfSystemProperty(named = "ranking.redis.integration", matches = "true")
    void redisCachesSuccessfulEmptyAndPreparingWithDifferentTtls() {
        jdbc.update("UPDATE restaurant_ranking_snapshots SET items='[]' WHERE campus_code='QINGSHUIHE' AND period_type='WEEK'");
        jdbc.update("DELETE FROM restaurant_ranking_snapshots WHERE campus_code='QINGSHUIHE' AND period_type='MONTH'");
        ranking.setRedisEnabled(true);
        for (int i = 0; i < 2; i++) {
            assertThat(service.read("QINGSHUIHE", "WEEK").getStatus()).isEqualTo("READY");
            assertThat(service.read("QINGSHUIHE", "MONTH").getStatus()).isEqualTo("PREPARING");
        }
        assertThat(probe.selects.get()).isEqualTo(2);
        assertThat(redis.getExpire(redisKey(0), TimeUnit.MILLISECONDS)).isBetween(7_190_000L, 7_200_000L);
        assertThat(redis.getExpire(redisKey(1), TimeUnit.MILLISECONDS)).isBetween(1L, 5000L);
    }

    @Test
    @EnabledIfSystemProperty(named = "ranking.redis.integration", matches = "true")
    void redisTtlCannotOutliveRemainingSnapshotAgeAndBadValuesAreRebuilt() {
        ranking.setRedisEnabled(true);
        service.read("QINGSHUIHE", "WEEK");
        redis.opsForValue().set(redisKey(0), "broken", Duration.ofHours(2));
        probe.selects.set(0);
        assertThat(service.read("QINGSHUIHE", "WEEK").getItems()).hasSize(20);
        assertThat(probe.selects.get()).isEqualTo(1);
        redis.delete(redisKey(0));
        var row = snapshots.selectSnapshot("QINGSHUIHE", "WEEK");
        var end = Instant.now().minus(Duration.ofHours(24)).plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        row.setWindowEndAt(end);
        row.setWindowStartAt(end.minus(Duration.ofHours(168)));
        row.setGeneratedAt(Instant.now().minusSeconds(1));
        snapshots.upsertSnapshot(row);
        assertThat(service.read("QINGSHUIHE", "WEEK").getStatus()).isEqualTo("READY");
        assertThat(redis.getExpire(redisKey(0), TimeUnit.MILLISECONDS)).isBetween(1L, 60_000L);
        // 人工给超龄旧值设置更长TTL，读取仍须按原统计截止时间拒绝，并回源当前快照。
        row.setWindowEndAt(end.minus(Duration.ofHours(2)));
        row.setWindowStartAt(row.getWindowEndAt().minus(Duration.ofHours(168)));
        redis.opsForValue().set(redisKey(0), codec.encodeCache(row, RestaurantRankingKey.ALL.getFirst(), Instant.now()), Duration.ofHours(2));
        probe.selects.set(0);
        assertThat(service.read("QINGSHUIHE", "WEEK").getStatus()).isEqualTo("READY");
        assertThat(probe.selects.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @EnabledIfSystemProperty(named = "ranking.redis.integration", matches = "true")
    void publicationCommitsFourRowsThenInvalidatesAndRejectsOlderRounds(boolean caffeine) {
        ranking.setCaffeineEnabled(caffeine);
        ranking.setRedisEnabled(true);
        for (String[] key : KEYS) {
            service.read(key[0], key[1]);
        }
        var round = nextRound();
        publisher.publish(round);
        for (int i = 0; i < KEYS.length; i++) {
            assertThat(redis.hasKey(redisKey(i))).isFalse();
            assertThat(service.read(KEYS[i][0], KEYS[i][1]).getItems()).isEmpty();
        }
        var older = nextRound();
        older.forEach(row -> {
            row.setWindowStartAt(row.getWindowStartAt().minusSeconds(1));
            row.setWindowEndAt(row.getWindowEndAt().minusSeconds(1));
        });
        assertThatThrownBy(() -> publisher.publish(older)).isInstanceOf(com.foodmap.common.exception.BusinessException.class);
        assertThat(snapshots.selectSnapshot("QINGSHUIHE", "WEEK").getWindowEndAt()).isEqualTo(round.getFirst().getWindowEndAt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @EnabledIfSystemProperty(named = "ranking.redis.integration", matches = "true")
    void failedPublicationRollsBackAllRowsAndKeepsSuccessfulOldCache(boolean caffeine) {
        ranking.setCaffeineEnabled(caffeine);
        ranking.setRedisEnabled(true);
        service.read("QINGSHUIHE", "WEEK");
        String old = redis.opsForValue().get(redisKey(0));
        var round = nextRound();
        probe.writes.set(0);
        probe.failOnWrite = 2;
        assertThatThrownBy(() -> publisher.publish(round)).isInstanceOf(RuntimeException.class);
        probe.failOnWrite = 0;
        for (String[] key : KEYS) {
            assertThat(snapshots.selectSnapshot(key[0], key[1]).getWindowEndAt()).isEqualTo(cutoff);
        }
        assertThat(redis.opsForValue().get(redisKey(0))).isEqualTo(old);
        probe.selects.set(0);
        assertThat(service.read("QINGSHUIHE", "WEEK").getItems()).hasSize(20);
        assertThat(probe.selects.get()).isZero();
        assertThat(local.get(RestaurantRankingKey.ALL.getFirst()) != null).isEqualTo(caffeine);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @EnabledIfSystemProperty(named = "ranking.redis.integration", matches = "true")
    void readerWaitsForActualCommitAndCannotRefillTheOldSnapshot(boolean caffeine) throws Exception {
        ranking.setCaffeineEnabled(caffeine);
        ranking.setRedisEnabled(true);
        service.read("QINGSHUIHE", "WEEK");
        String old = redis.opsForValue().get(redisKey(0));
        // 清本机模拟本机未命中；未失效的热读可返回提交前旧榜，冷加载必须等待真实提交。
        local.invalidate(RestaurantRankingKey.ALL);
        var beforeCommit = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var registered = new java.util.concurrent.atomic.AtomicBoolean();
        probe.beforeWrite = () -> {
            if (registered.compareAndSet(false, true)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        beforeCommit.countDown();
                        await(allowCommit);
                    }
                });
            }
        };
        var round = nextRound();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var publication = executor.submit(() -> publisher.publish(round));
            assertThat(beforeCommit.await(3, TimeUnit.SECONDS)).isTrue();
            var read = executor.submit(() -> service.read("QINGSHUIHE", "WEEK"));
            try {
                assertThat(redis.opsForValue().get(redisKey(0))).isEqualTo(old);
                assertThat(snapshots.selectSnapshot("QINGSHUIHE", "WEEK").getWindowEndAt()).isEqualTo(cutoff);
                assertThatThrownBy(() -> read.get(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                allowCommit.countDown();
            }
            publication.get(3, TimeUnit.SECONDS);
            assertThat(read.get(3, TimeUnit.SECONDS).getItems()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @EnabledIfSystemProperty(named = "ranking.redis.integration", matches = "true")
    void redisColdConcurrentReadsOnlySelectMysqlOnce(boolean caffeine) throws Exception {
        ranking.setCaffeineEnabled(caffeine);
        ranking.setRedisEnabled(true);
        long gets = redisCommandCounts().get("get");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(16)) {
            var reads = java.util.stream.IntStream.range(0, 16).mapToObj(i -> executor.submit(() -> {
                await(start);
                return service.read("QINGSHUIHE", "WEEK");
            })).toList();
            start.countDown();
            for (var read : reads) {
                assertThat(read.get(3, TimeUnit.SECONDS).getItems()).hasSize(20);
            }
        }
        assertThat(probe.selects.get()).isEqualTo(1);
        assertThat(redisCommandCounts().get("get") - gets).isEqualTo(caffeine ? 1 : 16);
    }

    /** 只在显式授权的私有Redis实例运行ACL故障，不对共享开发Redis执行权限变更。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @EnabledIfSystemProperty(named = "ranking.redis.faults", matches = "true")
    void failedRedisDeleteAfterCommitBypassesStaleValueAndRepairs(boolean caffeine) {
        ranking.setCaffeineEnabled(caffeine);
        ranking.setRedisEnabled(true);
        service.read("QINGSHUIHE", "WEEK");
        String old = redis.opsForValue().get(redisKey(0));
        var round = nextRound();
        command("ACL", "SETUSER", "default", "-del");
        try {
            publisher.publish(round);
            assertThat(snapshots.selectSnapshot("QINGSHUIHE", "WEEK").getItems()).isEqualTo("[]");
            assertThat(redis.opsForValue().get(redisKey(0))).isEqualTo(old);
            assertThat(service.read("QINGSHUIHE", "WEEK").getItems()).isEmpty();
        } finally {
            command("ACL", "SETUSER", "default", "+del");
        }
        awaitInvalidation();
        local.invalidate(RestaurantRankingKey.ALL);
        probe.selects.set(0);
        assertThat(service.read("QINGSHUIHE", "WEEK").getItems()).isEmpty();
        assertThat(service.read("QINGSHUIHE", "WEEK").getItems()).isEmpty();
        assertThat(probe.selects.get()).isEqualTo(1);
    }

    /** 暂停私有Redis真实模拟无响应，验证专用200ms超时而非共享客户端默认等待。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @EnabledIfSystemProperty(named = "ranking.redis.faults", matches = "true")
    void unresponsiveRedisFallsBackWithinBoundedTimeAndRecovers(boolean caffeine) {
        ranking.setCaffeineEnabled(caffeine);
        ranking.setRedisEnabled(true);
        service.read("QINGSHUIHE", "WEEK");
        local.invalidate(RestaurantRankingKey.ALL);
        jdbc.update("UPDATE restaurant_ranking_snapshots SET items='[]' WHERE campus_code='QINGSHUIHE' AND period_type='WEEK'");
        command("CLIENT", "PAUSE", "2000", "ALL");
        long start = System.nanoTime();
        var result = service.read("QINGSHUIHE", "WEEK");
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(result.getItems()).isEmpty();
        assertThat(elapsed).isLessThan(1500);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).ignoreExceptions().until(() -> {
            try (var connection = redis.getConnectionFactory().getConnection()) {
                return "PONG".equals(connection.ping());
            }
        });
        awaitInvalidation();
        local.invalidate(RestaurantRankingKey.ALL);
        probe.selects.set(0);
        service.read("QINGSHUIHE", "WEEK");
        service.read("QINGSHUIHE", "WEEK");
        assertThat(probe.selects.get()).isEqualTo(1);
        System.out.println("Ranking Redis pause fallback caffeine=" + caffeine + " elapsedMs=" + elapsed);
    }

    private void awaitInvalidation() {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> {
            cache.recover();
            return !redis.hasKey(redisKey(0));
        });
    }

    @Test
    @EnabledIfSystemProperty(named = "ranking.caffeine.integration", matches = "true")
    void caffeineRefillsFromRedisThenHttpHitsDoNotReadEitherLowerStore() throws Exception {
        ranking.setRedisEnabled(true);
        var baseline = service.read("QINGSHUIHE", "WEEK");
        probe.selects.set(0);
        long before = redisCommandCounts().get("get");
        ranking.setCaffeineEnabled(true);
        assertThat(service.read("QINGSHUIHE", "WEEK")).isEqualTo(baseline);
        long ttl = redis.getExpire(redisKey(0), TimeUnit.MILLISECONDS);
        try (var client = client()) {
            for (int i = 0; i < 10; i++) {
                var response = client.send(request(0), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertReady(response, 0);
                assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
                assertThat(response.body()).doesNotContain("rulesSnapshot", "sortingScore", "fixtureOnly");
            }
        }
        assertThat(probe.selects.get()).isZero();
        assertThat(redisCommandCounts().get("get") - before).isEqualTo(1);
        assertThat(redis.getExpire(redisKey(0), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
    }

    @Test
    @EnabledIfSystemProperty(named = "ranking.caffeine.integration", matches = "true")
    void caffeineHotHitsDoNotDecodeBrokenRedisValue() {
        ranking.setRedisEnabled(true);
        ranking.setCaffeineEnabled(true);
        var expected = service.read("QINGSHUIHE", "WEEK");
        long before = redisCommandCounts().get("get");
        probe.selects.set(0);
        // DEL不会影响本机；直接破坏下层值，证明热读无需解码或回源。
        redis.opsForValue().set(redisKey(0), "broken", Duration.ofMinutes(1));
        assertThat(service.read("QINGSHUIHE", "WEEK")).isEqualTo(expected);
        assertThat(probe.selects.get()).isZero();
        assertThat(redisCommandCounts().get("get") - before).isZero();
    }

    private void command(String name, String... arguments) {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection ->
                connection.execute(name, Arrays.stream(arguments)
                        .map(value -> value.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new)));
    }

    private List<RestaurantRankingSnapshotEntity> nextRound() {
        return RestaurantRankingKey.ALL.stream().map(key -> {
            var row = snapshots.selectSnapshot(key.campusCode(), key.periodType());
            row.setWindowEndAt(cutoff.plusSeconds(60));
            row.setWindowStartAt(row.getWindowEndAt().minus(Duration.ofHours(key.windowHours())));
            row.setGeneratedAt(cutoff.plusSeconds(90));
            row.setItems("[]");
            return row;
        }).toList();
    }

    private String redisKey(int key) {
        return cache.redisKey(new RestaurantRankingKey(KEYS[key][0], KEYS[key][1]));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("Test synchronization timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    /**
     * 可复跑的读取基线：各场景至少预热15秒及2000次，再各5轮、每轮1000次。
     * 区分服务读取、串行HTTP和8并发HTTP，避免短预热下的JIT变化被误记为缓存收益。
     * 真实调用计时，结果断言位于单次计时外；SQL探针统计实际prepare，不把Mapper调用当作SQL执行。
     */
    @Test
    @EnabledIfSystemProperty(named = "ranking.read.benchmark", matches = "true")
    void benchmarkMysqlReadBaseline() throws Exception {
        var output = Path.of(System.getProperty("ranking.benchmark.output",
                "target/ranking-read-benchmark.json")).toAbsolutePath();
        var report = new LinkedHashMap<String, Object>();
        boolean compareRedis = Boolean.getBoolean("ranking.redis.integration");
        boolean compareCaffeine = Boolean.getBoolean("ranking.caffeine.integration");
        var modes = compareCaffeine ? List.of("MYSQL_ONLY", "REDIS", "CAFFEINE")
                : compareRedis ? List.of("MYSQL_ONLY", "REDIS") : List.of("MYSQL_ONLY");
        int roundCount = compareCaffeine ? 6 : 5;
        report.put("stage", compareCaffeine ? "MYSQL_REDIS_CAFFEINE_COMPARISON"
                : compareRedis ? "MYSQL_REDIS_COMPARISON" : "MYSQL_ONLY");
        report.put("modes", modes);
        report.put("comparisonOrder", "even rounds reverse mode order; all modes share one JVM and fixtures");
        if (compareCaffeine) {
            report.put("comparisonOrder", "six rounds cover all permutations of three modes in one JVM");
            report.put("caffeineTtlSeconds", ranking.getCaffeineTtl().toSeconds());
            report.put("caffeineMaximumSize", RestaurantRankingKey.ALL.size());
        }
        if (compareRedis) {
            try (var connection = redis.getConnectionFactory().getConnection()) {
                report.put("redisVersion", connection.serverCommands().info("server").getProperty("redis_version"));
            }
        }
        report.put("startedAt", Instant.now().toString());
        report.put("environment", Map.of("java", System.getProperty("java.runtime.version"),
                "vm", System.getProperty("java.vm.name"), "os", System.getProperty("os.name"),
                "arch", System.getProperty("os.arch"), "logicalProcessors", Runtime.getRuntime().availableProcessors(),
                "mysql", jdbc.queryForObject("SELECT VERSION()", String.class),
                "innodbBufferPoolBytes", jdbc.queryForObject("SELECT @@innodb_buffer_pool_size", Long.class),
                "hikariMaxPool", 10, "network", "loopback"));
        var fixture = new ArrayList<String>();
        for (String[] key : KEYS) {
            fixture.add(key[0] + "/" + key[1] + "/" + rules() + "/" + items(key[0], key[1]));
        }
        report.put("fixture", Map.of("boards", 4, "itemsPerBoard", 20,
                "sha256WithoutTimestamps", sha256(String.join("\n", fixture)),
                "statisticsCutoff", cutoff.toString(), "synthetic", true,
                "mysqlJsonBytes", jdbc.queryForObject("SELECT SUM(OCTET_LENGTH(items)+OCTET_LENGTH(rules_snapshot))"
                        + " FROM restaurant_ranking_snapshots", Long.class)));
        report.put("mapperSha256", sha256(new String(Objects.requireNonNull(getClass().getResourceAsStream(
                "/mapper/restaurant/RestaurantRankingSnapshotMapper.xml")).readAllBytes(), StandardCharsets.UTF_8)));
        report.put("queryPlan", jdbc.queryForList("EXPLAIN SELECT campus_code,period_type,window_start_at,"
                + "window_end_at,generated_at,rules_snapshot,items FROM restaurant_ranking_snapshots"
                + " WHERE campus_code='QINGSHUIHE' AND period_type='WEEK'"));
        report.put("warmupMinimumSecondsPerScenario", 15);
        report.put("warmupMinimumRequestsPerScenario", 2000);
        report.put("measuredRequestsPerRound", 1000);
        report.put("roundsPerScenario", roundCount);
        report.put("percentileMethod", "nearest-rank");
        report.put("limitations", List.of("合成快照读取，不测点评聚合或冷磁盘", "本机客户端、应用与MySQL共享资源",
                "闭环有限并发，非最大吞吐或生产压测", "SQL计数探针仅在测试启用", "HTTP匿名请求，无JWT会话读取",
                "单次HTTP时间包括网络、安全过滤、JSON序列化和接收完整响应，不含测试结果校验",
                "服务读取时间不含HTTP及响应序列化；SQL数据页及连接池均已预热"));
        var rounds = new ArrayList<Map<String, Object>>();
        var warmups = new ArrayList<Map<String, Object>>();
        try (var client = client()) {
            report.put("httpResponseBytes", client.send(request(0), HttpResponse.BodyHandlers.ofByteArray()).body().length);
            for (String mode : modes) {
                selectMode(mode);
                for (String scenario : List.of("service", "http_serial", "http_c8")) {
                    long start = System.nanoTime();
                    int requests = 0;
                    do {
                        runRound(client, mode, scenario, -1, 500);
                        requests += 500;
                    } while (requests < 2000 || System.nanoTime() - start < Duration.ofSeconds(15).toNanos());
                    warmups.add(Map.of("mode", mode, "scenario", scenario, "requests", requests,
                            "elapsedMs", (System.nanoTime() - start) / 1_000_000.0));
                }
            }
            for (int round = 1; round <= roundCount; round++) {
                var order = new ArrayList<>(modes);
                if (compareCaffeine) {
                    java.util.Collections.rotate(order, -(round - 1) % 3);
                    if (round > 3) {
                        java.util.Collections.reverse(order);
                    }
                } else if (round % 2 == 0) {
                    java.util.Collections.reverse(order);
                }
                for (String mode : order) {
                    selectMode(mode);
                    for (String scenario : List.of("service", "http_serial", "http_c8")) {
                        rounds.add(runRound(client, mode, scenario, round, 1000));
                    }
                }
            }
        }
        report.put("warmups", warmups);
        report.put("rounds", rounds);
        report.put("finishedAt", Instant.now().toString());
        Files.createDirectories(output.getParent());
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        assertThat(rounds).hasSize(3 * roundCount * modes.size());
        System.out.println("Ranking read benchmark completed; evidence=" + output);
    }

    /** 同一JVM只切换榜单读取层，不更改夹具、连接池或公开结果；热缓存预热不计入正式样本。 */
    private void selectMode(String mode) {
        ranking.setRedisEnabled(!mode.equals("MYSQL_ONLY"));
        ranking.setCaffeineEnabled(mode.equals("CAFFEINE"));
    }

    private Map<String, Object> runRound(HttpClient client, String mode, String scenario, int round, int count) throws Exception {
        int concurrency = scenario.equals("http_c8") ? 8 : 1;
        long[] samples = new long[count];
        long sqlBefore = probe.selects.get();
        var redisBefore = redisCommandCounts();
        long started = System.nanoTime();
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
            for (int worker = 0; worker < concurrency; worker++) {
                final int lane = worker;
                tasks.add(() -> {
                    for (int i = lane; i < count; i += concurrency) {
                        int key = i % KEYS.length;
                        if (scenario.equals("service")) {
                            long begin = System.nanoTime();
                            var value = service.read(KEYS[key][0], KEYS[key][1]);
                            samples[i] = System.nanoTime() - begin;
                            assertThat(value.getStatus()).isEqualTo("READY");
                            assertThat(value.getItems()).hasSize(20);
                            assertThat(value.getCampusCode()).isEqualTo(KEYS[key][0]);
                            assertThat(value.getPeriodType()).isEqualTo(KEYS[key][1]);
                        } else {
                            var request = request(key);
                            long begin = System.nanoTime();
                            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                            samples[i] = System.nanoTime() - begin;
                            assertReady(response, key);
                        }
                    }
                    return null;
                });
            }
            for (var future : executor.invokeAll(tasks)) {
                future.get();
            }
        }
        long elapsed = System.nanoTime() - started;
        long selects = probe.selects.get() - sqlBefore;
        if (mode.equals("MYSQL_ONLY")) {
            assertThat(selects).isEqualTo(count);
        } else if (round > 0) {
            assertThat(selects).isZero();
        } else {
            assertThat(selects).isBetween(0L, 4L);
        }
        var redisAfter = redisCommandCounts();
        long redisGets = redisAfter.get("get") - redisBefore.get("get");
        if (Boolean.getBoolean("ranking.redis.integration")) {
            if (mode.equals("CAFFEINE") && round < 0) {
                assertThat(redisGets).isBetween(0L, 4L);
            } else {
                assertThat(redisGets).isEqualTo(mode.equals("REDIS") ? count : 0);
            }
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        var result = new LinkedHashMap<String, Object>();
        result.put("mode", mode);
        result.put("scenario", scenario);
        result.put("round", round);
        result.put("concurrency", concurrency);
        result.put("requests", count);
        result.put("errors", 0);
        result.put("mysqlSelects", selects);
        for (String command : List.of("get", "set", "del")) {
            result.put("redis_" + command, redisAfter.get(command) - redisBefore.get(command));
        }
        result.put("elapsedMsIncludingValidation", elapsed / 1_000_000.0);
        result.put("completedRequestsPerSecond", count * 1_000_000_000.0 / elapsed);
        result.put("meanMs", Arrays.stream(samples).average().orElseThrow() / 1_000_000.0);
        for (int p : new int[] {50, 95, 99}) {
            result.put("p" + p + "Ms", sorted[(int) Math.ceil(count * p / 100.0) - 1] / 1_000_000.0);
        }
        result.put("rawLatencyNs", samples);
        return result;
    }

    /** 私有测试实例的INFO commandstats计数，放在单次计时外，不以Mock调用次数代替Redis执行。 */
    private Map<String, Long> redisCommandCounts() {
        var result = new LinkedHashMap<String, Long>();
        java.util.Properties info = new java.util.Properties();
        if (Boolean.getBoolean("ranking.redis.integration")) {
            try (var connection = redis.getConnectionFactory().getConnection()) {
                info = connection.serverCommands().info("commandstats");
            }
        }
        for (String command : List.of("get", "set", "del")) {
            String value = info.getProperty("cmdstat_" + command, "calls=0");
            var matcher = java.util.regex.Pattern.compile("(?:^|,)calls=(\\d+)").matcher(value);
            if (!matcher.find()) {
                throw new IllegalStateException("Missing Redis command counter");
            }
            result.put(command, Long.parseLong(matcher.group(1)));
        }
        return result;
    }

    private void assertReady(HttpResponse<String> response, int key) {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("code").stringValue()).isEqualTo("OK");
        var data = body.get("data");
        assertThat(data.get("status").stringValue()).isEqualTo("READY");
        assertThat(data.get("campusCode").stringValue()).isEqualTo(KEYS[key][0]);
        assertThat(data.get("periodType").stringValue()).isEqualTo(KEYS[key][1]);
        assertThat(data.get("items").size()).isEqualTo(20);
        assertThat(data.get("items").get(0).get("restaurantId").stringValue()).isEqualTo("9007199254740993");
    }

    private HttpRequest request(int key) {
        return HttpRequest.newBuilder(URI.create(baseUrl() + "?campusCode=" + KEYS[key][0]
                        + "&periodType=" + KEYS[key][1])).timeout(Duration.ofSeconds(5)).GET().build();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + "/api/restaurant-rankings";
    }

    private HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
    }

    private String rules() {
        return "{\"formatVersion\":1,\"scoreScale\":5,\"fixtureOnly\":true}";
    }

    private String items(String campus, String period) {
        var values = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 20; i++) {
            String id = Long.toString(9007199254740993L + i);
            values.add(new TreeMap<>(Map.of("rank", i + 1, "restaurantId", id,
                    "name", "基准餐厅-" + campus + "-" + period + "-" + (i + 1),
                    "coverUrl", "/api/restaurants/" + id + "/photos/1", "tasteScore", 4.5,
                    "participantCount", 30 + i, "sortingScore", 4.123456)));
        }
        return json.writeValueAsString(values);
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), username(), password());
    }

    private static String url(String name) {
        String server = System.getenv().getOrDefault("RANKING_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Ranking read tests require loopback MySQL");
        }
        return server + name + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String username() {
        return System.getenv().getOrDefault("RANKING_TEST_DB_USERNAME", "root");
    }

    private static String password() {
        return Objects.requireNonNull(System.getenv("RANKING_TEST_DB_PASSWORD"), "Set RANKING_TEST_DB_PASSWORD");
    }

    /** 只在隔离测试上下文注册，不在正式运行链路附加计数或计时开销。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        SnapshotSqlProbe rankingSnapshotSqlProbe() {
            return new SnapshotSqlProbe();
        }
    }

    /** 统计实际SQL prepare，排除JDBC夹具写入；未来缓存命中时这个数字应下降。 */
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    static class SnapshotSqlProbe implements Interceptor {
        private final AtomicLong selects = new AtomicLong();
        private final AtomicLong writes = new AtomicLong();
        private volatile Runnable beforeWrite;
        private volatile int failOnWrite;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (StatementHandler) invocation.getTarget();
            String sql = statement.getBoundSql().getSql().stripLeading();
            if (sql.startsWith("SELECT") && sql.contains("FROM restaurant_ranking_snapshots")) {
                selects.incrementAndGet();
            }
            if (sql.startsWith("INSERT INTO restaurant_ranking_snapshots")) {
                long count = writes.incrementAndGet();
                if (beforeWrite != null) {
                    beforeWrite.run();
                }
                if (failOnWrite > 0 && count == failOnWrite) {
                    throw new org.springframework.dao.DataAccessResourceFailureException("Injected isolated publication failure");
                }
            }
            return invocation.proceed();
        }
    }
}
