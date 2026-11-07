package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

import com.ginkgo.foodmap.restaurant.config.*;
import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.search.*;
import com.ginkgo.foodmap.restaurant.service.*;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.service.LoginSessionService;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** 真实MySQL/ES隔离验收：仅创建随机库及索引，不读写真实业务商户或正式别名。 */
@EnabledIfSystemProperty(named = "ginkgo.search.it", matches = "true")
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.sql.init.mode=never",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.datasource.hikari.minimum-idle=0",
            "app.search.elasticsearch.enabled=true",
            "app.search.query.enabled=true",
            "app.search.sync.enabled=false"
        })
@Import(TestJwtKeys.class)
class RestaurantSearchQueryIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired JdbcTemplate jdbc;
    @Autowired ElasticsearchClient client;
    @Autowired ObjectMapper json;
    @Autowired RestaurantSearchProjectionService projections;
    @Autowired RestaurantQueryService queries;
    @Autowired RestaurantReadService mysql;
    @Autowired CircuitBreaker breaker;
    @Autowired MeterRegistry meters;
    @Autowired RestaurantSearchQueryProperties properties;
    @MockitoSpyBean RestaurantSearchReader reader;
    @MockitoBean LoginSessionService sessions;
    @MockitoBean com.ginkgo.foodmap.file.service.MediaFileService media;
    private String index;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        database = "ginkgo_search_it_" + UUID.randomUUID().toString().replace("-", "");
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
                () -> System.getenv().getOrDefault("RESTAURANT_TEST_DB_USERNAME", "root"));
        registry.add(
                "spring.datasource.password",
                () -> Objects.requireNonNull(System.getenv("RESTAURANT_TEST_DB_PASSWORD")));
        registry.add(
                "app.search.elasticsearch.address",
                () -> System.getProperty("ginkgo.es.address", "127.0.0.1:9200"));
    }

    @BeforeEach
    void prepare() throws Exception {
        reset(reader);
        breaker.reset();
        properties.setEnabled(true);
        jdbc.update("DELETE FROM restaurant_search_outbox");
        jdbc.update("DELETE FROM restaurant_review_statistics");
        jdbc.update("DELETE FROM restaurants");
        insert(101, "清水河咖啡", "清水河", 30, "30.750000", "103.930000");
        insert(102, "清水河咖啡分店", "清水河", 20, "30.750000", "103.930020");
        insert(103, "测试咖啡", "清水河", null, null, null);
        insert(104, "沙河咖啡", "沙河", 10, "30.600000", "104.100000");
        insert(105, "清水河面馆", "清水河", 15, "30.750000", "103.930000");
        insert(106, "边界咖啡", "清水河", 50, "30.760000", "103.940000");
        jdbc.update("UPDATE restaurants SET supports_dine_in=TRUE WHERE id IN (101,106)");
        jdbc.update("UPDATE restaurants SET supports_dine_in=FALSE WHERE id=102");
        jdbc.update("UPDATE restaurants SET business_status='CLOSED' WHERE id=106");
        index = "ginkgo-search-it-" + UUID.randomUUID().toString().replace("-", "");
        properties.setIndexAlias(index + "-alias");
        String definition;
        try (var stream =
                getClass().getResourceAsStream("/elasticsearch/restaurant-search-v003.json")) {
            var mapping = json.readValue(stream, Map.class);
            mapping.remove("aliases");
            definition = json.writeValueAsString(mapping);
        }
        client.indices()
                .create(
                        b ->
                                b.index(index)
                                        .withJson(new StringReader(definition))
                                        .aliases(
                                                properties.getIndexAlias(),
                                                a -> a.isWriteIndex(true)));
        publish(List.of(101L, 102L, 103L, 104L, 105L, 106L));
    }

    @AfterEach
    void removeIndex() throws Exception {
        if (index != null
                && index.matches("ginkgo-search-it-[a-f0-9]{32}")
                && client.indices().exists(b -> b.index(index)).value()) {
            client.indices().delete(b -> b.index(index));
        }
    }

    @AfterAll
    static void removeDatabase() throws Exception {
        if (created && database.matches("ginkgo_search_it_[a-f0-9]{32}")) {
            try (var connection = connect("");
                    var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE `" + database + "`");
            }
        }
    }

    @Test
    void exactNameWinsAndCombinationMatchesAcrossFieldsBeforePaging() throws Exception {
        var q = query("清水河咖啡");
        q.setPageSize(2);
        assertThat(reader.search(q, false).getTotal()).isEqualTo(4);
        var first = queries.list(q);
        assertThat(first.getSearch().getEngine()).isEqualTo("ES");
        assertThat(first.getTotal()).isEqualTo(4);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getRecords().getFirst().getId()).isEqualTo("101");
        q.setPage(2);
        var second = queries.list(q);
        assertThat(second.getTotal()).isEqualTo(4);
        var ids = new HashSet<String>();
        first.getRecords().forEach(r -> ids.add(r.getId()));
        second.getRecords().forEach(r -> ids.add(r.getId()));
        assertThat(ids).containsExactlyInAnyOrder("101", "102", "103", "106");
        assertThat(queries.list(query("不存在的独角兽咖啡")).getTotal()).isZero();
    }

    /** 单字按原字包含；不继承MySQL排序规则将〇扩展为ASCII数字0的行为。 */
    @Test
    void singleHanUsesLiteralCharacterInsteadOfMysqlNumericCollation() throws Exception {
        jdbc.update("UPDATE restaurants SET name='〇号咖啡店',search_projection_version=2 WHERE id=101");
        jdbc.update("UPDATE restaurants SET name='0号咖啡店',search_projection_version=2 WHERE id=102");
        publish(List.of(101L, 102L));
        var result = queries.list(query("〇"));
        assertThat(result.getSearch().getEngine()).isEqualTo("ES");
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).extracting(r -> r.getId()).containsExactly("101");
    }

    /** 四类文字均支持单字包含，补充平面与Han数字保留；列表和地图仍统一业务排序。 */
    @Test
    void singleCharactersUseEsForNameAddressCategoryAndTags() throws Exception {
        jdbc.update("INSERT INTO restaurant_tags(restaurant_id,tag,position) VALUES(101,'聚餐推荐',0)");
        jdbc.update(
                "UPDATE restaurants SET name='清水河咖啡𠀀〇',search_projection_version=2 WHERE id=101");
        publish(List.of(101L));
        try {
            for (String word : List.of("咖", "沙", "他", "荐", "𠀀", "〇")) {
                var q = query(word);
                q.setPageSize(1);
                var es = queries.list(q);
                var sql = mysql.list(q);
                assertThat(es.getSearch().getEngine()).isEqualTo("ES");
                assertThat(es.getSearch().getEffectiveSort()).isEqualTo("score");
                assertThat(es.getTotal()).isEqualTo(sql.getTotal()).isPositive();
                assertThat(es.getRecords())
                        .extracting(r -> r.getId())
                        .containsExactlyElementsOf(
                                sql.getRecords().stream().map(r -> r.getId()).toList());
                bounds(q, "103", "105", "30", "32");
                var map = queries.mapPoints(q);
                var sqlMap = mysql.mapPoints(q);
                assertThat(map.getSearch().getEngine()).isEqualTo("ES");
                assertThat(map.getMatchedCount()).isEqualTo(sqlMap.getMatchedCount());
                assertThat(map.getPoints())
                        .extracting(r -> r.getId())
                        .containsExactlyElementsOf(
                                sqlMap.getPoints().stream().map(r -> r.getId()).toList());
            }
            var q = query("咖");
            q.setSort("price");
            q.setPage(2);
            q.setPageSize(1);
            assertThat(queries.list(q).getRecords())
                    .extracting(r -> r.getId())
                    .containsExactlyElementsOf(
                            mysql.list(q).getRecords().stream().map(r -> r.getId()).toList());
        } finally {
            jdbc.update("DELETE FROM restaurant_tags WHERE restaurant_id=101");
        }
    }

    /** 旧结构没有辅助字段时必须整次SQL降级，不能静默返回0家。 */
    @Test
    void missingCharacterFieldsFallBackInsteadOfPretendingNoMatches() throws Exception {
        String old = index + "-old";
        boolean created = false;
        try {
            String definition;
            try (var stream =
                    getClass().getResourceAsStream("/elasticsearch/restaurant-search-v002.json")) {
                var mapping = json.readValue(stream, Map.class);
                mapping.remove("aliases");
                definition = json.writeValueAsString(mapping);
            }
            client.indices().create(b -> b.index(old).withJson(new StringReader(definition)));
            created = true;
            properties.setIndexAlias(old);
            var result = queries.list(query("咖"));
            assertThat(result.getTotal()).isEqualTo(5);
            assertThat(result.getSearch().isDegraded()).isTrue();
            assertThat(result.getSearch().getReason()).isEqualTo("ES_UNAVAILABLE");
        } finally {
            if (created) {
                client.indices().delete(b -> b.index(old));
            }
        }
    }

    @Test
    void hardFiltersApplyToTotalPageAndMapAndUnknownIsNotTrueOrZero() {
        var q = query("清水河咖啡");
        q.setDiningMode("dineIn");
        q.setHideClosed(true);
        q.setMaxPrice(new BigDecimal("30"));
        assertThat(queries.list(q).getRecords()).extracting(r -> r.getId()).containsExactly("101");
        bounds(q, "103", "105", "30", "32");
        var points = queries.mapPoints(q);
        assertThat(points.getSearch().getEngine()).isEqualTo("ES");
        assertThat(points.getMatchedCount()).isEqualTo(1);
        assertThat(points.getPoints()).extracting(r -> r.getId()).containsExactly("101");
        q.setDiningMode("delivery");
        assertThat(queries.list(q).getTotal()).isZero();
        q.setDiningMode("");
        q.setRestaurantType("CANTEEN");
        assertThat(queries.list(q).getTotal()).isZero();
    }

    @Test
    void sourceCoordinatesPreserveInclusiveViewportEdgesAndUnknownLocation() {
        var q = query("清水河咖啡");
        bounds(q, "103.940000", "103.950000", "30.760000", "30.770000");
        var es = queries.list(q);
        var sql = new RestaurantListQuery();
        sql.setKeyword("咖啡");
        bounds(sql, "103.940000", "103.950000", "30.760000", "30.770000");
        assertThat(es.getSearch().getEngine()).isEqualTo("ES");
        assertThat(es.getRecords()).extracting(r -> r.getId()).containsExactly("106");
        assertThat(es.getTotal()).isEqualTo(mysql.list(sql).getTotal());
        assertThat(queries.mapPoints(q).getMatchedCount()).isEqualTo(1);
        bounds(q, "103.940001", "103.950000", "30.760000", "30.770000");
        assertThat(queries.list(q).getTotal()).isZero();
    }

    @Test
    void radiusAndDistanceOrderAgreeWithSqlAndDoNotIncludeUnknownCoordinates() {
        var q = query("咖啡");
        q.setLatitude(new BigDecimal("30.75"));
        q.setLongitude(new BigDecimal("103.93"));
        q.setSort("distance");
        q.setMaxDistanceMeters(1);
        assertThat(queries.list(q).getRecords()).extracting(r -> r.getId()).containsExactly("101");
        q.setMaxDistanceMeters(2);
        var es = queries.list(q);
        q.setSearchOrderById(true);
        assertThat(es.getSearch().getEngine()).isEqualTo("ES");
        assertThat(es.getRecords()).extracting(r -> r.getId()).containsExactly("101", "102");
        assertThat(es.getRecords())
                .extracting(r -> r.getId())
                .containsExactlyElementsOf(
                        mysql.list(q).getRecords().stream().map(r -> r.getId()).toList());
        q.setMaxDistanceMeters(null);
        assertThat(queries.list(q).getRecords().getLast().getId()).isEqualTo("103");
    }

    @Test
    void manualPriceAndReviewOrderAreGlobalAndSqlFallbackUsesTheSameTies() {
        for (String sort : List.of("price", "reviews")) {
            var q = query("咖啡");
            q.setSort(sort);
            q.setPageSize(2);
            q.setPage(2);
            var es = queries.list(q);
            assertThat(es.getSearch().getEngine()).isEqualTo("ES");
            q.setSearchOrderById(true);
            var sql = mysql.list(q);
            assertThat(es.getRecords())
                    .extracting(r -> r.getId())
                    .containsExactlyElementsOf(
                            sql.getRecords().stream().map(r -> r.getId()).toList());
            assertThat(es.getTotal()).isEqualTo(sql.getTotal());
        }
    }

    @Test
    void changedDeletedAndRestoredRowsNeverReuseOldEsTotal() throws Exception {
        jdbc.update("UPDATE restaurants SET name='资料已改',search_projection_version=2 WHERE id=101");
        var changed = queries.list(query("清水河咖啡"));
        assertThat(changed.getSearch().getReason()).isEqualTo("DATA_CHANGED");
        assertThat(changed.getTotal()).isEqualTo(1); // SQL完整字符串匹配仅保留102，不复用ES的4条总数。
        jdbc.update(
                "UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),delete_reason_code='SYSTEM',search_projection_version=3 WHERE id=102");
        var deleted = queries.list(query("咖啡"));
        assertThat(deleted.getSearch().getReason()).isEqualTo("DATA_CHANGED");
        assertThat(deleted.getRecords()).extracting(r -> r.getId()).doesNotContain("102");
        publish(List.of(101L, 102L));
        jdbc.update(
                "UPDATE restaurants SET deleted_at=NULL,delete_reason_code=NULL,search_projection_version=4 WHERE id=102");
        jdbc.update(
                "INSERT INTO restaurant_search_outbox(restaurant_id,projection_version) VALUES(102,4)");
        var pending = queries.list(query("咖啡"));
        assertThat(pending.getSearch().getReason()).isEqualTo("SYNC_PENDING");
        assertThat(pending.getRecords()).extracting(r -> r.getId()).contains("102");
        jdbc.update("UPDATE restaurant_search_outbox SET status='FAILED'");
        assertThat(queries.list(query("咖啡")).getSearch().getReason()).isEqualTo("SYNC_PENDING");
        publish(List.of(102L));
        jdbc.update("UPDATE restaurant_search_outbox SET status='SUCCEEDED'");
        assertThat(queries.list(query("咖啡")).getSearch().getEngine()).isEqualTo("ES");
    }

    /** 一位小数后的同分以ID排序；含舍入临界值、未知/零、全局分页及SQL降级一致性。 */
    @Test
    void roundedRatingSortsStayOnEsAndAgreeWithSql() throws Exception {
        jdbc.update(
                "UPDATE restaurants SET latitude=30.75,longitude=103.93004,needs_location=FALSE WHERE id=103");
        rating(101, 10000, 9234, "82400"); // 8.24 -> 8.2，92.34% -> 92.3%
        rating(102, 100000, 92349, "824900"); // 更高尾数仍同档，不能排到较小ID前面。
        rating(103, 10000, 9235, "82500"); // 8.25 -> 8.3，92.35% -> 92.4%
        rating(104, 1, 0, "0"); // 真实零应排在未知106之前。
        publish(List.of(101L, 102L, 103L, 104L));
        for (String sort : List.of("score", "recommend")) {
            var q = query("咖啡");
            q.setSort(sort);
            var es = queries.list(q);
            assertThat(es.getSearch().getEngine()).isEqualTo("ES");
            assertThat(es.getRecords())
                    .extracting(r -> r.getId())
                    .containsExactly("103", "101", "102", "104", "106");
            assertThat(es.getRecords().getFirst().getScore()).isEqualByComparingTo("8.3");
            assertThat(es.getRecords().getFirst().getRecommendRate()).isEqualByComparingTo("0.924");
            assertThat(es.getRecords().get(1).getScore()).isEqualByComparingTo("8.2");
            assertThat(es.getRecords().get(1).getRecommendRate()).isEqualByComparingTo("0.923");
            assertThat(es.getRecords().getLast().getScore()).isNull();
            assertThat(es.getRecords())
                    .extracting(r -> r.getId())
                    .containsExactlyElementsOf(
                            mysql.list(q).getRecords().stream().map(r -> r.getId()).toList());
            q.setPageSize(1);
            q.setPage(3);
            assertThat(queries.list(q).getRecords())
                    .extracting(r -> r.getId())
                    .containsExactly("102");
            bounds(q, "103", "105", "30", "32");
            var map = queries.mapPoints(q);
            assertThat(map.getSearch().getEngine()).isEqualTo("ES");
            assertThat(map.getPoints())
                    .extracting(r -> r.getId())
                    .containsExactly("103", "101", "102", "104", "106");
            // 组合词匹配集合不因评分/好评率排序而退回LIKE（SQL此组合只命中101、102）。
            q.setKeyword("清水河咖啡");
            q.setPage(1);
            var combined = queries.list(q);
            assertThat(combined.getSearch().getEngine()).isEqualTo("ES");
            assertThat(combined.getTotal()).isEqualTo(4);
            assertThat(queries.mapPoints(q).getMatchedCount()).isEqualTo(4);
        }
        // 缺失索引触发真实SQL降级，保留显式排序及相同舍入/ID次序。
        properties.setIndexAlias(index + "-missing");
        for (String sort : List.of("score", "recommend")) {
            var q = query("咖啡");
            q.setSort(sort);
            var fallback = queries.list(q);
            assertThat(fallback.getSearch().isDegraded()).isTrue();
            assertThat(fallback.getSearch().getEffectiveSort()).isEqualTo(sort);
            assertThat(fallback.getRecords())
                    .extracting(r -> r.getId())
                    .containsExactly("103", "101", "102", "104", "106");
        }
    }

    private void rating(long id, long participants, long recommended, String sum) {
        jdbc.update(
                "INSERT INTO restaurant_review_statistics(restaurant_id,review_count,participant_count,recommend_count,overall_score_sum) VALUES(?,?,?,?,?)",
                id,
                participants,
                participants,
                recommended,
                sum);
        jdbc.update("UPDATE restaurants SET search_projection_version=2 WHERE id=?", id);
    }

    @Test
    void missingIndexFallsBackAndNetworkHasNoDatabaseTransaction() throws Exception {
        doAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return invocation.callRealMethod();
                        })
                .when(reader)
                .search(any(), anyBoolean());
        properties.setIndexAlias(index + "-missing");
        var q = query("咖啡");
        var result = queries.list(q);
        assertThat(result.getSearch().getReason()).isEqualTo("ES_UNAVAILABLE");
        assertThat(result.getTotal()).isEqualTo(5);
        assertThat(client.indices().exists(b -> b.index(properties.getIndexAlias())).value())
                .isFalse();
        bounds(q, "103", "105", "30", "32");
        assertThat(queries.mapPoints(q).getSearch().isDegraded()).isTrue();
    }

    /** 真实连接拒绝累计到10次后熔断；列表/地图共享，10秒后真实单字请求恢复。 */
    @Test
    void realConnectionRefusalTripsSharedBreakerAndRecoversWithoutStoppingSharedEs()
            throws Exception {
        int port;
        try (var reservation =
                new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = reservation.getLocalPort();
        }
        var disconnected =
                ElasticsearchClient.of(
                        b ->
                                b.host("http://127.0.0.1:" + port)
                                        .jsonMapper(client._transport().jsonpMapper()));
        try {
            var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            beans.registerSingleton(
                    "offlineReader", new RestaurantSearchReader(disconnected, json, properties));
            var offline =
                    new RestaurantQueryService(
                            mysql,
                            beans.getBeanProvider(RestaurantSearchReader.class),
                            properties,
                            breaker,
                            meters);
            var q = query("咖啡");
            var list = offline.list(q);
            assertThat(list.getTotal()).isEqualTo(5);
            assertThat(list.getSearch().getReason()).isEqualTo("ES_UNAVAILABLE");
            bounds(q, "103", "105", "30", "32");
            assertThat(offline.mapPoints(q).getSearch().isDegraded()).isTrue();
            for (int i = 0; i < 8; i++) {
                offline.list(q);
            }
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(10);
            var skipped = offline.mapPoints(q);
            assertThat(skipped.getSearch().getReason()).isEqualTo("ES_CIRCUIT_OPEN");
            assertThat(skipped.getMatchedCount()).isEqualTo(4);
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(10);
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(10050);
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            q.setKeyword("咖");
            var recovered = queries.mapPoints(q);
            assertThat(recovered.getSearch().getEngine()).isEqualTo("ES");
            assertThat(recovered.getMatchedCount()).isEqualTo(4);
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        } finally {
            disconnected._transport().close();
        }
    }

    @Test
    void mapCapAndExactTotalDoNotDependOnListPagination() throws Exception {
        var ids = new ArrayList<Long>();
        for (long id = 200; id < 305; id++) {
            insert(id, "点位咖啡" + id, "清水河", 20, "30.75", "103.93");
            ids.add(id);
        }
        publish(ids.subList(0, 100));
        publish(ids.subList(100, 105));
        var q = query("点位咖啡");
        q.setSort("price");
        q.setPage(3);
        q.setPageSize(8);
        bounds(q, "103", "105", "30", "32");
        var list = queries.list(q);
        var map = queries.mapPoints(q);
        assertThat(list.getTotal()).isEqualTo(105);
        assertThat(list.getRecords()).hasSize(8);
        assertThat(map.getMatchedCount()).isEqualTo(105);
        assertThat(map.getPoints()).hasSize(100);
        assertThat(map.isTruncated()).isTrue();
    }

    private void insert(
            long id, String name, String address, Integer price, String lat, String lon) {
        jdbc.update(
                "INSERT INTO restaurants(id,name,category_code,address,price_kind,price_per_person,latitude,longitude,needs_location) VALUES(?,?,'OTHER',?,?,?,?,?,?)",
                id,
                name,
                address,
                price == null ? "UNKNOWN" : "EXACT",
                price,
                lat,
                lon,
                lat == null);
    }

    private void publish(List<Long> ids) throws Exception {
        var settings = new RestaurantSearchSyncProperties();
        settings.setIndexAlias(properties.getIndexAlias());
        var writer = new RestaurantSearchIndexWriter(client, json, settings);
        // 投影服务每批最多100店；测试同样遵守边界。
        for (var document : projections.assemble(ids)) {
            writer.write(document);
        }
        client.indices().refresh(b -> b.index(index));
    }

    private static RestaurantListQuery query(String keyword) {
        var q = new RestaurantListQuery();
        q.setKeyword(keyword);
        return q;
    }

    private static void bounds(RestaurantListQuery q, String w, String e, String s, String n) {
        q.setWest(new BigDecimal(w));
        q.setEast(new BigDecimal(e));
        q.setSouth(new BigDecimal(s));
        q.setNorth(new BigDecimal(n));
    }

    private static String url(String name) {
        String server =
                System.getenv()
                        .getOrDefault("RESTAURANT_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Loopback database required");
        }
        return server
                + name
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(
                url(name),
                System.getenv().getOrDefault("RESTAURANT_TEST_DB_USERNAME", "root"),
                Objects.requireNonNull(System.getenv("RESTAURANT_TEST_DB_PASSWORD")));
    }
}
