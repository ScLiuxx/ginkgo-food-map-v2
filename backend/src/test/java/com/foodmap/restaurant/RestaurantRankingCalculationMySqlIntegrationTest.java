package com.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foodmap.restaurant.config.RestaurantRankingProperties;
import com.foodmap.restaurant.entity.RestaurantRankingSnapshotEntity;
import com.foodmap.restaurant.mapper.RestaurantRankingCandidateMapper;
import com.foodmap.restaurant.service.RestaurantRankingCalculationService;
import com.foodmap.restaurant.service.RestaurantRankingGenerationService;
import com.foodmap.restaurant.service.RestaurantRankingReadService;
import com.foodmap.restaurant.support.RestaurantRankingKey;
import com.foodmap.restaurant.support.RestaurantRankingSnapshotCodec;
import com.foodmap.review.dto.RestaurantPeriodReviewStats;
import com.foodmap.review.service.ReviewRankingService;
import com.foodmap.security.TestJwtKeys;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 显式开启的真实MySQL计算验证：Flyway随机隔离库，绝不向开发库写模拟餐厅或点评。 验证窗口函数、微秒边界、硬删除回退、可见性及RR快照，不能用H2模拟MySQL语义替代。 */
@EnabledIfSystemProperty(named = "ranking.calculation.integration", matches = "true")
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.sql.init.mode=never",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "app.ranking.redis-enabled=false",
            "app.ranking.caffeine-enabled=false",
            "app.search.elasticsearch.enabled=false",
            "app.search.sync.enabled=false",
            "app.search.query.enabled=false",
            "app.file.oss.enabled=false"
        })
@Import({
    TestJwtKeys.class,
    RestaurantRankingCalculationMySqlIntegrationTest.ProbeConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RestaurantRankingCalculationMySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-11T08:00:00Z");
    private static final Instant T = NOW.minusSeconds(60);
    private static String database;
    private static boolean created;
    private static final AtomicLong REVIEW_IDS = new AtomicLong(1000);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RestaurantRankingCalculationService calculation;
    @Autowired private RestaurantRankingGenerationService generation;
    @Autowired private RestaurantRankingReadService rankingRead;
    @Autowired private ReviewRankingService reviews;
    @Autowired private RestaurantRankingCandidateMapper candidates;
    @Autowired private RestaurantRankingProperties properties;
    @Autowired private RestaurantRankingSnapshotCodec codec;
    @Autowired private ObjectMapper json;
    @Autowired private CalculationProbe probe;

    /** 凭据仅经环境传递，URL固定回环服务器加随机库名，不允许指定业务库。 */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) throws SQLException {
        database = "foodmap_ranking_calc_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add(
                "spring.datasource.username",
                RestaurantRankingCalculationMySqlIntegrationTest::username);
        registry.add(
                "spring.datasource.password",
                RestaurantRankingCalculationMySqlIntegrationTest::password);
    }

    @BeforeEach
    void reset() {
        probe.beforeAggregate = null;
        probe.candidateReads.set(0);
        probe.aggregateReads.set(0);
        // 均为本测试随机库内直接创建的无子资源夹具，不删除开发数据或触发公开业务操作。
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM restaurant_photos");
        jdbc.update("DELETE FROM restaurant_change_requests");
        jdbc.update("DELETE FROM restaurants");
        jdbc.update("DELETE FROM restaurant_ranking_snapshots");
        var defaults = new RestaurantRankingProperties();
        properties.setQingshuihe(defaults.getQingshuihe());
        properties.setShahe(defaults.getShahe());
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (created) {
            if (!database.matches("foodmap_ranking_calc_it_[a-f0-9]{32}")) {
                throw new IllegalStateException("Unexpected ranking calculation database name");
            }
            try (var connection = connect("");
                    var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE `" + database + "`");
            }
        }
    }

    @Test
    void latestPerUserUsesTimestampThenIdAndHardDeleteFallsBackOnlyWithinPeriod() {
        restaurant(1, false);
        review(1L, 1, T.minusSeconds(100), "10");
        long latest = review(1L, 1, T.minusSeconds(100), "8");
        review(1L, 2, T.minusSeconds(200), "9");
        review(1L, 3, T.minusSeconds(300), "9");
        var first = aggregate().getFirst();
        assertThat(first.getParticipantCount()).isEqualTo(3);
        assertThat(first.getTasteSum()).isEqualByComparingTo("26");
        jdbc.update("DELETE FROM reviews WHERE id=?", latest);
        assertThat(aggregate().getFirst().getTasteSum()).isEqualByComparingTo("28");
        jdbc.update("DELETE FROM reviews WHERE author_user_id=1 AND restaurant_id=1");
        review(1L, 1, T.minus(Duration.ofDays(7)).minusNanos(1000), "10");
        assertThat(aggregate().getFirst().getParticipantCount()).isEqualTo(2);
        assertThat(items(calculation.calculate(T), "QINGSHUIHE", "WEEK")).hasSize(1);
        assertThat(items(calculation.calculate(T), "QINGSHUIHE", "MONTH")).hasSize(1);
    }

    @Test
    void halfOpenMicrosecondBoundariesAndEachPeriodsDedupAreIndependent() {
        for (long id = 1; id <= 6; id++) {
            restaurant(id, false);
        }
        Instant[] times = {
            T.minus(Duration.ofDays(7)),
            T.minus(Duration.ofDays(7)).minusNanos(1000),
            T,
            T.minus(Duration.ofDays(30)),
            T.minus(Duration.ofDays(30)).minusNanos(1000),
            T.plusSeconds(1)
        };
        for (int i = 0; i < times.length; i++) {
            threeReviews(i + 1, times[i], "8");
        }
        var result = calculation.calculate(T);
        assertThat(ids(items(result, "QINGSHUIHE", "WEEK"))).containsExactly("1");
        assertThat(ids(items(result, "QINGSHUIHE", "MONTH"))).containsExactly("1", "2", "4");
        assertThat(probe.candidateReads.get()).isEqualTo(1);
        assertThat(probe.aggregateReads.get()).isEqualTo(2);
    }

    @Test
    void latestLowReviewIsNotReplacedByOlderHighScoreAndUsersDoNotLeakAcrossRestaurants() {
        restaurant(1, false);
        restaurant(2, false);
        threeReviews(1, T.minus(Duration.ofDays(10)), "10");
        threeReviews(1, T.minusSeconds(100), "8");
        review(1L, 1, T.minusSeconds(50), "0");
        threeReviews(2, T.minusSeconds(100), "9");
        var result = calculation.calculate(T);
        assertThat(ids(items(result, "QINGSHUIHE", "WEEK"))).containsExactly("2");
        assertThat(ids(items(result, "QINGSHUIHE", "MONTH"))).containsExactly("2");
        assertThat(aggregate().getFirst().getTasteSum()).isEqualByComparingTo("16");
    }

    @Test
    void candidateVisibilityLocationAndApprovedCoverAreEnforced() {
        for (long id = 1; id <= 7; id++) {
            restaurant(id, false);
            threeReviews(id, T.minusSeconds(100), "10");
        }
        jdbc.update(
                "UPDATE restaurants SET restaurant_type='CANTEEN',category_code=NULL WHERE id=2");
        jdbc.update("UPDATE restaurants SET business_status='CLOSED' WHERE id=3");
        jdbc.update(
                "UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM'"
                        + " WHERE id=4");
        jdbc.update(
                "UPDATE restaurants SET needs_location=1,latitude=NULL,longitude=NULL WHERE id=5");
        jdbc.update("UPDATE restaurants SET latitude=31.7544 WHERE id=6");
        request(1, 1, true);
        request(2, 7, false);
        jdbc.update(
                "INSERT INTO"
                    + " restaurant_photos(id,restaurant_id,media_file_id,position,source_request_id)"
                    + " VALUES(101,1,1,0,1),(102,7,2,0,2)");
        var rows = candidates.selectCandidates();
        assertThat(rows).extracting(row -> row.getId()).containsExactly(1L, 6L, 7L);
        var items = items(calculation.calculate(T), "QINGSHUIHE", "WEEK");
        assertThat(ids(items)).containsExactly("1", "7");
        assertThat(items.get(0).get("coverUrl").stringValue())
                .isEqualTo("/api/restaurants/1/photos/101");
        assertThat(items.get(1).get("coverUrl").isNull()).isTrue();
    }

    @Test
    void campusBoardsUseConfiguredGcjCentersAndCanOverlapWithoutSplittingReviewers() {
        restaurant(1, false);
        restaurant(2, true);
        threeReviews(1, T.minusSeconds(100), "9");
        threeReviews(2, T.minusSeconds(100), "10");
        var result = calculation.calculate(T);
        assertThat(ids(items(result, "QINGSHUIHE", "WEEK"))).containsExactly("1");
        assertThat(ids(items(result, "SHAHE", "WEEK"))).containsExactly("2");
        properties.getShahe().setLatitude(properties.getQingshuihe().getLatitude());
        properties.getShahe().setLongitude(properties.getQingshuihe().getLongitude());
        var overlap = calculation.calculate(T);
        assertThat(ids(items(overlap, "SHAHE", "WEEK"))).containsExactly("1");
        assertThat(items(overlap, "SHAHE", "WEEK").get(0).get("participantCount").longValue())
                .isEqualTo(3);
    }

    @Test
    void calculatedResultsFitReaderAndKeepEvidenceWithoutPublishingOrChangingStatistics() {
        restaurant(1, false);
        threeReviews(1, T.minusSeconds(100), "9");
        long before =
                jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_search_outbox", Long.class);
        var result = calculation.calculate(T);
        for (int i = 0; i < result.size(); i++) {
            var row = result.get(i);
            assertThat(row.getWindowEndAt()).isEqualTo(T);
            assertThat(row.getGeneratedAt()).isEqualTo(NOW);
            var view =
                    codec.view(
                            row,
                            RestaurantRankingKey.ALL.get(i),
                            NOW,
                            properties.getMaxSnapshotAge());
            assertThat(view.getStatus()).isEqualTo("READY");
            var rules = json.readTree(row.getRulesSnapshot());
            assertThat(rules.get("weights").get("taste").decimalValue())
                    .isEqualByComparingTo("0.70");
            assertThat(rules.get("campus").get("radiusMeters").doubleValue()).isEqualTo(5000);
        }
        var item = items(result, "QINGSHUIHE", "WEEK").get(0);
        assertThat(item.get("tasteScore").decimalValue()).isEqualByComparingTo("4.5");
        assertThat(item.get("scoreSums").get("taste").decimalValue()).isEqualByComparingTo("13.5");
        assertThat(item.get("sortingScore").decimalValue()).isEqualByComparingTo("3.875");
        assertThat(
                        json.writeValueAsString(
                                codec.view(
                                        result.getFirst(),
                                        RestaurantRankingKey.ALL.getFirst(),
                                        NOW,
                                        properties.getMaxSnapshotAge())))
                .doesNotContain("scoreSums", "sortingScore", "rulesSnapshot");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM restaurant_ranking_snapshots", Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM restaurant_review_statistics", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_search_outbox", Long.class))
                .isEqualTo(before);
    }

    @Test
    void repeatableReadKeepsBothPeriodsInSameViewWhenReviewsChangeBetweenQueries() {
        restaurant(1, false);
        threeReviews(1, T.minusSeconds(100), "9");
        probe.beforeAggregate =
                () -> {
                    // 新连接真实提交，发生在候选SELECT之后、首次点评聚合之前；不依赖线程sleep推测顺序。
                    try (var connection = connect(database);
                            var statement = connection.createStatement()) {
                        statement.executeUpdate("DELETE FROM reviews WHERE restaurant_id=1");
                    } catch (SQLException exception) {
                        throw new IllegalStateException(exception);
                    }
                };
        var result = calculation.calculate(T);
        assertThat(items(result, "QINGSHUIHE", "WEEK")).hasSize(1);
        assertThat(items(result, "QINGSHUIHE", "MONTH")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews", Long.class)).isZero();
        assertThat(items(calculation.calculate(T), "QINGSHUIHE", "WEEK")).isEmpty();
    }

    private List<RestaurantPeriodReviewStats> aggregate() {
        return reviews.aggregate(T.minus(Duration.ofDays(7)), T);
    }

    @Test
    void generationCommitsFourBoardsAndLaterPublishesSuccessfulEmptyResults() {
        restaurant(1, false);
        threeReviews(1, T.minusSeconds(100), "9");
        assertThat(generation.generate()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM restaurant_ranking_snapshots", Long.class))
                .isEqualTo(4);
        var first = rankingRead.read("QINGSHUIHE", "WEEK");
        assertThat(first.getStatus()).isEqualTo("READY");
        assertThat(first.getItems()).hasSize(1);
        assertThat(first.getWindowEndAt()).isEqualTo(NOW);
        jdbc.update("DELETE FROM reviews");
        // 固定Clock同T重算也允许发布；真实服务不得把计算事务延伸到发布阶段。
        assertThat(generation.generate()).isTrue();
        for (var key : RestaurantRankingKey.ALL) {
            var view = rankingRead.read(key.campusCode(), key.periodType());
            assertThat(view.getStatus()).isEqualTo("READY");
            assertThat(view.getItems()).isEmpty();
        }
    }

    /** 空榜必须能被单人合格候选替换，贯穿计算、四榜发布及读取，不只验证排序函数。 */
    @Test
    void singleReviewerAndLowTasteWeightedCandidateCanReplaceOldEmptyBoards() {
        assertThat(generation.generate()).isTrue();
        restaurant(1, false);
        restaurant(2, true);
        review(1L, 1, T.minusSeconds(100), "10");
        long lowTaste = review(2L, 2, T.minusSeconds(100), "10");
        jdbc.update("UPDATE reviews SET taste_score=7 WHERE id=?", lowTaste);
        // 第二家原始加权3.95、口味3.5，平滑分3.575；仍应参与并成功发布。
        assertThat(generation.generate()).isTrue();
        for (var key : RestaurantRankingKey.ALL) {
            var view = rankingRead.read(key.campusCode(), key.periodType());
            assertThat(view.getStatus()).isEqualTo("READY");
            assertThat(view.getItems()).hasSize(1);
            var item = view.getItems().getFirst();
            assertThat(item.getParticipantCount()).isEqualTo(1);
            if ("SHAHE".equals(key.campusCode())) {
                assertThat(item.getTasteScore()).isEqualByComparingTo("3.5");
                assertThat(item.getRecommendationScore()).isEqualByComparingTo("3.6");
            }
        }
    }

    @Test
    void failedGenerationKeepsAllPreviouslyCommittedBoardsAndCanRecover() {
        restaurant(1, false);
        threeReviews(1, T.minusSeconds(100), "9");
        assertThat(generation.generate()).isTrue();
        var old =
                jdbc.queryForList(
                        "SELECT * FROM restaurant_ranking_snapshots ORDER BY"
                                + " campus_code,period_type");
        jdbc.update("DELETE FROM reviews");
        probe.beforeAggregate =
                () -> {
                    throw new IllegalStateException("isolated calculation failure");
                };
        assertThatThrownBy(generation::generate).isInstanceOf(RuntimeException.class);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM restaurant_ranking_snapshots ORDER BY"
                                        + " campus_code,period_type"))
                .isEqualTo(old);
        assertThat(rankingRead.read("QINGSHUIHE", "WEEK").getItems()).hasSize(1);
        assertThat(generation.generate()).isTrue();
        assertThat(rankingRead.read("QINGSHUIHE", "WEEK").getItems()).isEmpty();
    }

    @Test
    void invalidAggregationIntervalsFailBeforeExecutingSql() {
        assertThatThrownBy(() -> reviews.aggregate(null, T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviews.aggregate(T, T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviews.aggregate(T.plusNanos(1000), T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> reviews.aggregate(T.minus(Duration.ofDays(30)).minusNanos(1000), T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(probe.aggregateReads.get()).isZero();
    }

    private void restaurant(long id, boolean shahe) {
        var area = shahe ? properties.getShahe() : properties.getQingshuihe();
        jdbc.update(
                "INSERT INTO"
                    + " restaurants(id,name,category_code,restaurant_type,latitude,longitude,needs_location)"
                    + " VALUES(?,?,'OTHER','REGULAR',?,?,0)",
                id,
                "计算测试餐厅" + id,
                area.getLatitude(),
                area.getLongitude());
    }

    private void threeReviews(long restaurant, Instant time, String score) {
        for (int user = 1; user <= 3; user++) {
            review(restaurant, user, time, score);
        }
    }

    private long review(Long restaurant, long author, Instant time, String score) {
        long id = REVIEW_IDS.incrementAndGet();
        var value = new BigDecimal(score);
        jdbc.update(
                "INSERT INTO"
                    + " reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,recommendation,body,client_request_id,request_hash,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,'RECOMMENDED','仅用于计算的点评',UUID_TO_BIN(?),UNHEX(REPEAT('ab',32)),?)",
                id,
                restaurant,
                author,
                value,
                value,
                value,
                value,
                UUID.randomUUID().toString(),
                Timestamp.from(time));
        return id;
    }

    private void request(long id, long restaurant, boolean approved) {
        jdbc.update(
                "INSERT INTO"
                    + " restaurant_change_requests(id,restaurant_id,request_type,submitted_by,client_request_id,request_hash,proposed_data,status,reviewed_by,reviewed_at,approved_data,before_data)"
                    + " VALUES(?,?,'UPDATE',1,?,UNHEX(REPEAT('ab',32)),'{}',?,?,?, ?,?)",
                id,
                restaurant,
                UUID.randomUUID().toString(),
                approved ? "APPROVED" : "PENDING",
                approved ? 1L : null,
                approved ? Timestamp.from(T) : null,
                approved ? "{}" : null,
                approved ? "{}" : null);
    }

    private JsonNode items(
            List<RestaurantRankingSnapshotEntity> rows, String campus, String period) {
        return json.readTree(
                rows.stream()
                        .filter(
                                row ->
                                        row.getCampusCode().equals(campus)
                                                && row.getPeriodType().equals(period))
                        .findFirst()
                        .orElseThrow()
                        .getItems());
    }

    private static List<String> ids(JsonNode items) {
        var result = new ArrayList<String>();
        items.forEach(item -> result.add(item.get("restaurantId").stringValue()));
        return result;
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), username(), password());
    }

    private static String url(String name) {
        String server =
                System.getenv()
                        .getOrDefault("RANKING_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Ranking calculation tests require loopback MySQL");
        }
        return server + name + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String username() {
        return System.getenv().getOrDefault("RANKING_TEST_DB_USERNAME", "root");
    }

    private static String password() {
        return Objects.requireNonNull(
                System.getenv("RANKING_TEST_DB_PASSWORD"), "Set RANKING_TEST_DB_PASSWORD");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        /** 固定业务时间用于微秒边界；数据库夹具时间显式给定，不依赖机器运行时刻。 */
        @Bean
        @Primary
        Clock rankingCalculationTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        CalculationProbe rankingCalculationProbe() {
            return new CalculationProbe();
        }
    }

    /** 仅测试中注册的真实SQL拦截器，用确定性提交交错验证RR；不进入正式运行链路。 */
    @Intercepts(
            @Signature(
                    type = StatementHandler.class,
                    method = "prepare",
                    args = {Connection.class, Integer.class}))
    static class CalculationProbe implements Interceptor {
        private final AtomicLong candidateReads = new AtomicLong();
        private final AtomicLong aggregateReads = new AtomicLong();
        private Runnable beforeAggregate;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            String sql = ((StatementHandler) invocation.getTarget()).getBoundSql().getSql();
            if (sql.contains("FROM restaurants r") && sql.contains("cover_photo_id")) {
                candidateReads.incrementAndGet();
            }
            if (sql.contains("position_in_period")) {
                aggregateReads.incrementAndGet();
                var hook = beforeAggregate;
                beforeAggregate = null;
                if (hook != null) {
                    hook.run();
                }
            }
            return invocation.proceed();
        }
    }
}
