package com.ginkgo.foodmap.restaurant;

import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantCatalogMapper;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.service.LoginSessionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实MySQL 8.4、Flyway、Mapper、事务及安全过滤链的公开读取测试，不用H2替代排序或LIKE行为。
 * 显式开启restaurant.integration；只创建并清理自己的随机回环测试库，不加载本地业务配置或密钥。
 */
@EnabledIfSystemProperty(named = "restaurant.integration", matches = "true")
@ActiveProfiles("test")
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.minimum-idle=0", "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@Transactional
class RestaurantReadApiIntegrationTest {
    private static String database;
    private static boolean created;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private SqlSessionTemplate sqlSession;
    @MockitoBean private LoginSessionService sessions;
    @MockitoSpyBean private RestaurantCatalogMapper mapper;

    /** 上下文启动前创建空库，由实际应用Flyway配置执行V1至V3，验证XML自动加载。 */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) throws SQLException {
        String server = server();
        database = "ginkgo_restaurant_api_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connect(""); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(server, database));
        registry.add("spring.datasource.username", RestaurantReadApiIntegrationTest::username);
        registry.add("spring.datasource.password", RestaurantReadApiIntegrationTest::password);
    }

    /** 专用夹具覆盖停业、未知值、同名、多标签、删除及大整数ID，不导入任何生产内容。 */
    @BeforeEach
    void seedFixtures() {
        reset(mapper);
        insert(101, "A同名", "OTHER", 20, "ESTIMATE", "清水河Alpha路", 30.7544, 103.9312, "OPERATING");
        insert(102, "A同名", "OTHER", 20, "EXACT", "南门测试路", 30.7644, 103.9312, "OPERATING");
        insert(103, "B停业", "HOT_POT", null, "UNKNOWN", null, null, null, "CLOSED");
        insert(104, "C无位置", "SICHUAN", 50, "EXACT", "夹具地址", null, null, "OPERATING");
        insert(105, "D未知人均", "SNACK", null, "UNKNOWN", "远处", 31.7544, 103.9312, "OPERATING");
        insert(106, "E已删除", "OTHER", null, "UNKNOWN", "仅删除地址", null, null, "OPERATING");
        insert(107, "百分%_!\\测试", "OTHER", null, "UNKNOWN", null, null, null, "OPERATING");
        insert(9007199254740993L, "Z大ID", "OTHER", null, "UNKNOWN", null, null, null, "OPERATING");
        jdbc.update("UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6), delete_reason_code='SYSTEM' WHERE id=106");
        jdbc.update("UPDATE restaurants SET source_type='USER', created_by=42, updated_by=43, row_version=7 WHERE id=101");
        jdbc.update("INSERT INTO restaurant_tags(restaurant_id,tag,position) VALUES "
                + "(101,'招牌',0),(101,'测试搜索',1),(101,'测试组合',2),(102,'测试搜索',0),(106,'仅删除命中',0)");
    }

    /** 并发快照测试不用外层事务，其余测试回滚；此处只清理专属随机库中的夹具。 */
    @AfterEach
    void clearFixtures() {
        reset(mapper);
        jdbc.update("DELETE FROM restaurant_review_statistics");
        jdbc.update("DELETE FROM restaurant_tags");
        jdbc.update("DELETE FROM restaurants");
    }

    /** 非Flyway clean：只删除本测试实际创建并严格匹配前缀的数据库。 */
    @AfterAll
    static void dropDatabase() throws SQLException {
        if (!created) return;
        if (!database.matches("ginkgo_restaurant_api_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Refusing to remove an unexpected database name");
        }
        try (Connection connection = connect(""); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    /** 访客可读取13类字典，停用类仍可阅读，顺序由字典字段确定。 */
    @Test
    void shouldReadAllCategoriesInStableOrder() throws Exception {
        jdbc.update("UPDATE restaurant_categories SET enabled=0 WHERE code='OTHER'");
        mvc.perform(get("/api/restaurant-categories")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(13))
                .andExpect(jsonPath("$.data[0].code").value("HOT_POT"))
                .andExpect(jsonPath("$.data[12].code").value("OTHER"))
                .andExpect(jsonPath("$.data[12].enabled").value(false));
        mvc.perform(get("/api/restaurants").param("category", "OTHER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(4));
        verifyNoInteractions(sessions);
    }

    /** 真实分页不会因多标签重复或先分页再过滤漏项，同名由数字ID确定次序。 */
    @Test
    void shouldPageBeforeLoadingOrderedTags() throws Exception {
        mvc.perform(get("/api/restaurants").param("pageSize", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.totalPages").value(7))
                .andExpect(jsonPath("$.data.records[0].id").value("101"))
                .andExpect(jsonPath("$.data.records[0].tags[0]").value("招牌"))
                .andExpect(jsonPath("$.data.records[0].tags[2]").value("测试组合"));
        mvc.perform(get("/api/restaurants").param("pageSize", "1").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].id").value("102"));
        mvc.perform(get("/api/restaurants").param("pageSize", "1").param("page", "8"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.records").isEmpty());
    }

    /** 标签多个匹配仍只计数一次，AND组合及关闭停业过滤在全局集合执行。 */
    @Test
    void shouldSearchTagsAndCombineGlobalFilters() throws Exception {
        mvc.perform(get("/api/restaurants").param("keyword", " 测试 ").param("category", "OTHER")
                        .param("pageSize", "1").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records[0].id").value("102"));
        mvc.perform(get("/api/restaurants").param("category", "HOT_POT").param("hideClosed", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/restaurants").param("category", "HOT_POT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].businessStatus").value("CLOSED"));
    }

    /** 名称、地址、分类中文与ASCII大小写均按MySQL配置匹配，空白搜索不改变集合。 */
    @ParameterizedTest
    @ValueSource(strings = {"A同名", "alpha", "其他", "招牌", "   "})
    void shouldSearchEveryPublicTextField(String keyword) throws Exception {
        var result = mvc.perform(get("/api/restaurants").param("keyword", keyword))
                .andExpect(status().isOk()).andReturn();
        var data = json.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("total").asLong()).isGreaterThan(0);
        assertThat(data.get("records").get(0).get("id").asString()).isEqualTo("101");
    }

    /** LIKE通配符、转义符及反斜杠是文字；SQL注入片段只可能匹配实际文字。 */
    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!", "\\", "%_!\\"})
    void shouldSearchLiteralSpecialCharacters(String keyword) throws Exception {
        mvc.perform(get("/api/restaurants").param("keyword", keyword)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value("107"));
    }

    /** 用户文字不能成为SQL，非法排序在校验层拒绝。 */
    @Test
    void shouldRejectSqlOrderingAndTreatSqlLookingKeywordsAsText() throws Exception {
        mvc.perform(get("/api/restaurants").param("keyword", "' OR 1=1 --"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/restaurants").param("sort", "price; DROP TABLE restaurants"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isEqualTo(8);
    }

    /** 人均和直线距离均在全局排序后分页；未知值不能先于有效值。 */
    @ParameterizedTest
    @ValueSource(strings = {"price", "distance"})
    void shouldSortKnownValuesBeforeUnknowns(String sort) throws Exception {
        mvc.perform(get("/api/restaurants").param("sort", sort).param("pageSize", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].id").value("101"))
                .andExpect(jsonPath("$.data.records[1].id").value("102"));
        mvc.perform(get("/api/restaurants").param("sort", sort).param("pageSize", "1").param("page", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value("price".equals(sort) ? "104" : "105"));
    }

    /** 评分排序在没有评价阶段使用稳定后备次序，不为无评价店铺捏造分数。 */
    @ParameterizedTest
    @ValueSource(strings = {"score", "taste", "value", "service", "ambience", "reviews"})
    void shouldUseStableOrderForEmptyRatingSorts(String sort) throws Exception {
        mvc.perform(get("/api/restaurants").param("sort", sort).param("pageSize", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].id").value("101"));
    }

    /** 待评级不会被划入推荐或踩雷，五个有评分杯型在M1都没有命中。 */
    @ParameterizedTest
    @ValueSource(strings = {"bucket", "super", "large", "medium", "small"})
    void shouldExcludeUnratedFromScoredTiers(String tier) throws Exception {
        mvc.perform(get("/api/restaurants").param("tiers", tier)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    /** 杯型为并集，视图与杯型为交集；待评级并入组合仍不能变成推荐。 */
    @Test
    void shouldApplyViewsAndTierUnionConsistently() throws Exception {
        mvc.perform(get("/api/restaurants").param("tiers", "none", "small"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(7));
        for (String view : new String[]{"recommended", "pitfall"}) {
            mvc.perform(get("/api/restaurants").param("view", view).param("tiers", "none"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        }
    }

    /** 列表和详情均不泄露删除店铺，标签残留不让其通过搜索重新出现。 */
    @Test
    void shouldHideDeletedRestaurantsAtEveryEntry() throws Exception {
        mvc.perform(get("/api/restaurants/106")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESTAURANT_NOT_FOUND"));
        mvc.perform(get("/api/restaurants/999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESTAURANT_NOT_FOUND"));
        for (String keyword : new String[]{"E已删除", "仅删除地址", "仅删除命中"}) {
            mvc.perform(get("/api/restaurants").param("keyword", keyword)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_tags WHERE restaurant_id=106", Long.class))
                .isEqualTo(1);
    }

    /** 输出白名单与null评分由实际JSON验证，内部归属、版本和删除字段均不返回。 */
    @Test
    void shouldReturnPublicDetailsWithNullScoresAndUtcTime() throws Exception {
        jdbc.update("UPDATE restaurants SET created_at='2026-10-28 01:02:03.123456' WHERE id=101");
        var result = mvc.perform(get("/api/restaurants/101")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("101"))
                .andExpect(jsonPath("$.data.priceKind").value("ESTIMATE"))
                .andExpect(jsonPath("$.data.sourceType").value("USER"))
                .andExpect(jsonPath("$.data.tags.length()").value(3))
                .andExpect(jsonPath("$.data.distanceKm").value(0.0))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-28T01:02:03.123456Z"))
                .andReturn();
        var data = json.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("score").isNull()).isTrue();
        assertThat(data.get("recommendRate").isNull()).isTrue();
        for (String field : new String[]{"taste", "value", "service", "ambience"}) {
            assertThat(data.get("dimensions").get(field).isNull()).isTrue();
        }
        assertThat(data.get("reviewCount").asLong()).isZero();
        assertThat(data.get("participantCount").asLong()).isZero();
        assertThat(data.get("tier").asString()).isEqualTo("none");
        for (String field : new String[]{"createdBy", "updatedBy", "deletedBy", "deletedAt", "rowVersion", "deleteReasonCode"}) {
            assertThat(data.has(field)).as(field).isFalse();
        }
    }

    /** 大整数身份无精度损失，待定位与未知人均保留空值，停业详情仍可阅读。 */
    @Test
    void shouldReadUnknownsClosedRecordsAndLargeIds() throws Exception {
        mvc.perform(get("/api/restaurants/9007199254740993")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.needsLocation").value(true));
        var result = mvc.perform(get("/api/restaurants/103")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessStatus").value("CLOSED")).andReturn();
        var data = json.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("pricePerPerson").isNull()).isTrue();
        assertThat(data.get("latitude").isNull()).isTrue();
        assertThat(data.get("longitude").isNull()).isTrue();
        assertThat(data.get("distanceKm").isNull()).isTrue();
        assertThat(data.get("tags").isEmpty()).isTrue();
    }

    /** 放行仅限三个GET；写入、管理子资源和其他方法保持认证边界，坏Bearer也不能绕过。 */
    @Test
    void shouldKeepWriteAndManagementRoutesProtected() throws Exception {
        mvc.perform(post("/api/restaurants")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/restaurants/101")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/restaurants/101")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/restaurants/101/admin")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/restaurants").header("Authorization", "Bearer invalid-test-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    /** 在COUNT之后由另一连接删除餐厅和标签，当前读取仍须保持同一快照；下次读取才反映删除。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldUseOneSnapshotForCountPageAndTags() throws Exception {
        AtomicBoolean changed = new AtomicBoolean();
        doAnswer(call -> {
            // MyBatis Mapper 是接口代理，不能使用 Mockito callRealMethod；经同一事务执行真实映射语句。
            long total = sqlSession.selectOne(RestaurantCatalogMapper.class.getName() + ".countVisible",
                    Map.of("query", call.getArgument(0)));
            if (changed.compareAndSet(false, true)) {
                try (Connection writer = connect(database); Statement statement = writer.createStatement()) {
                    statement.executeUpdate("UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6), "
                            + "delete_reason_code='SYSTEM' WHERE id=101");
                    statement.executeUpdate("DELETE FROM restaurant_tags WHERE restaurant_id=101");
                }
            }
            return total;
        }).when(mapper).countVisible(any(RestaurantListQuery.class));
        mvc.perform(get("/api/restaurants")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.records.length()").value(7))
                .andExpect(jsonPath("$.data.records[0].id").value("101"))
                .andExpect(jsonPath("$.data.records[0].tags.length()").value(3));
        mvc.perform(get("/api/restaurants")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.records[0].id").value("102"));
    }

    /** 统计来源同属restaurant模块，目录和详情必须返回同一组人数、历史条数和独立推荐率。 */
    @Test
    void shouldExposeIdenticalStatisticsInListAndDetailWithoutWrites() throws Exception {
        statistics(101, 3, 2, 1, "15", "16", "15", "12", "17");
        var detail = mvc.perform(get("/api/restaurants/101")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewCount").value(3))
                .andExpect(jsonPath("$.data.participantCount").value(2))
                .andExpect(jsonPath("$.data.score").value(7.5))
                .andExpect(jsonPath("$.data.recommendRate").value(0.5))
                .andExpect(jsonPath("$.data.tier").value("super"))
                .andExpect(jsonPath("$.data.dimensions.taste").value(8))
                .andExpect(jsonPath("$.data.dimensions.value").value(7.5))
                .andExpect(jsonPath("$.data.dimensions.service").value(6))
                .andExpect(jsonPath("$.data.dimensions.ambience").value(8.5)).andReturn();
        var list = mvc.perform(get("/api/restaurants").param("pageSize", "1"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(list.getResponse().getContentAsString()).get("data").get("records").get(0))
                .isEqualTo(json.readTree(detail.getResponse().getContentAsString()).get("data"));
        // 读取缺失统计行及显式零行都不写数据库；只有首次发评才按既有流程惰性初始化。
        statistics(102, 0, 0, 0, "0", "0", "0", "0", "0");
        for (long id : new long[]{102, 103}) {
            mvc.perform(get("/api/restaurants/" + id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.participantCount").value(0))
                    .andExpect(jsonPath("$.data.tier").value("none"))
                    .andExpect(jsonPath("$.data.score").isEmpty());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_review_statistics", Long.class)).isEqualTo(2);
        verifyNoInteractions(sessions);
    }

    /** 所有真实评分维度与总条数先在完整集合排序，再分页；同分仍按名称和ID稳定返回。 */
    @ParameterizedTest
    @ValueSource(strings = {"score", "taste", "value", "service", "ambience", "reviews"})
    void shouldSortRealStatisticsGloballyBeforePaging(String sort) throws Exception {
        statistics(101, 1, 1, 1, "7", "7", "7", "7", "7");
        statistics(102, 1, 1, 0, "7", "7", "7", "7", "7");
        statistics(105, 5, 2, 0, "18", "18", "18", "18", "18");
        for (int page = 1; page <= 3; page++) {
            String expected = new String[]{"105", "101", "102"}[page - 1];
            mvc.perform(get("/api/restaurants").param("sort", sort).param("pageSize", "1")
                            .param("page", Integer.toString(page)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].id").value(expected));
        }
    }

    /** 所有杯型临界点比较精确累计值，展示成8.6的8.599仍属超大杯。 */
    @ParameterizedTest
    @CsvSource({"8599,super", "8600,bucket", "7399,large", "7400,super", "6199,medium",
            "6200,large", "4999,small", "5000,medium", "0,small", "10000,bucket"})
    void shouldClassifyBeforeRoundingAndFilterUsingTheSameTier(String sum, String tier) throws Exception {
        statistics(101, 1000, 1000, 0, sum, sum, sum, sum, sum);
        mvc.perform(get("/api/restaurants/101")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tier").value(tier));
        mvc.perform(get("/api/restaurants").param("tiers", tier).param("pageSize", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value("101"));
        mvc.perform(get("/api/restaurants").param("tiers", "none"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(6));
    }

    /** 真实0分先于未知分；视图按杯型而非推荐率，组合筛选和总数使用相同口径。 */
    @Test
    void shouldDistinguishZeroUnknownAndIndependentRecommendationChoice() throws Exception {
        statistics(105, 1, 1, 1, "0", "0", "0", "0", "0");
        statistics(104, 1, 1, 0, "6.25", "6.5", "6.5", "6", "6");
        mvc.perform(get("/api/restaurants/105")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(0))
                .andExpect(jsonPath("$.data.recommendRate").value(1))
                .andExpect(jsonPath("$.data.tier").value("small"));
        mvc.perform(get("/api/restaurants").param("pageSize", "1").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].id").value("105"));
        mvc.perform(get("/api/restaurants").param("view", "recommended").param("tiers", "large", "none"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value("104"));
        mvc.perform(get("/api/restaurants").param("view", "pitfall").param("tiers", "small", "none"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value("105"));
        mvc.perform(get("/api/restaurants").param("view", "recommended").param("tiers", "small"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    /** 接近INT UNSIGNED人数上限仍保留除法差异，不因MySQL默认除法精度而错序或跨杯型。 */
    @Test
    void shouldPreserveSubMicroScoreDifferences() throws Exception {
        statistics(101, 4_000_000_000L, 4_000_000_000L, 0, "34399999999.99", "0", "0", "0", "0");
        statistics(102, 4_000_000_000L, 4_000_000_000L, 0, "34400000000", "0", "0", "0", "0");
        mvc.perform(get("/api/restaurants").param("pageSize", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].id").value("102"));
        mvc.perform(get("/api/restaurants/101")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tier").value("super"));
        mvc.perform(get("/api/restaurants").param("tiers", "bucket"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }

    /** COUNT后并发发布改变人数和杯型，本次响应仍保持同一只读快照，下次请求才看到新值。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldReadStatisticsAndFilterWithinOneSnapshot() throws Exception {
        statistics(101, 1, 1, 1, "8", "8", "8", "8", "8");
        var changed = new AtomicBoolean();
        doAnswer(call -> {
            long total = sqlSession.selectOne(RestaurantCatalogMapper.class.getName() + ".countVisible",
                    Map.of("query", call.getArgument(0)));
            if (changed.compareAndSet(false, true)) {
                try (Connection writer = connect(database); Statement statement = writer.createStatement()) {
                    statement.executeUpdate("UPDATE restaurant_review_statistics SET review_count=2, participant_count=2, "
                            + "overall_score_sum=9, taste_sum=9, value_sum=9, service_sum=9, ambience_sum=9 WHERE restaurant_id=101");
                }
            }
            return total;
        }).when(mapper).countVisible(any(RestaurantListQuery.class));
        mvc.perform(get("/api/restaurants").param("view", "recommended"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].score").value(8))
                .andExpect(jsonPath("$.data.records[0].participantCount").value(1));
        mvc.perform(get("/api/restaurants").param("view", "recommended"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/restaurants/101")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(4.5));
    }

    /** 金额条件包含30元边界，未知价格仅在开启上限时排除，默认目录不丢失未知值。 */
    @Test
    void shouldFilterMaximumPriceWithInclusiveBoundary() throws Exception {
        advancedFixtures();
        assertAdvanced("30", null, null, false, "201", "204", "206");
        assertAdvanced("29.99", null, null, false, "204", "206");
        assertAdvanced(null, null, null, false, "201", "202", "203", "204", "205", "206");
    }

    /** SQL使用米制未舍入Haversine；范围外、位置缺失均不命中，阈值附近不能因显示舍入误入。 */
    @Test
    void shouldFilterMaximumDistanceAndExcludeUnlocatedRestaurants() throws Exception {
        advancedFixtures();
        assertAdvanced(null, "1000", null, false, "201", "202", "205");
        mvc.perform(get("/api/restaurants").param("keyword", "高级").param("maxDistanceMeters", "1000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[1].distanceKm").value(org.hamcrest.Matchers.lessThanOrEqualTo(1.0)));
        // 纬度保留6位小数后约999米和1001米；计算误差远小于1米。
        insert(207, "高级G边界内", "OTHER", 30, "EXACT", null,
                30.7544 + Math.toDegrees(999.0 / 6371000), 103.9312, "OPERATING");
        insert(208, "高级H边界外", "OTHER", 30, "EXACT", null,
                30.7544 + Math.toDegrees(1001.0 / 6371000), 103.9312, "OPERATING");
        assertAdvanced(null, "1000", null, false, "201", "202", "205", "207");
    }

    /** 分类、金额、直线距离及经营状态按交集过滤，停业开关不改变其他条件语义。 */
    @Test
    void shouldCombineAdvancedFiltersWithCategoryAndClosedMarker() throws Exception {
        advancedFixtures();
        assertAdvanced("50", null, "HOT_POT", false, "201", "202", "203", "206");
        assertAdvanced(null, "2000", "HOT_POT", true, "201", "202", "205");
        assertAdvanced("30", "1000", null, false, "201");
        assertAdvanced("20", "500", "HOT_POT", true);
    }

    /** 全局筛选再按真实分数排序和分页；COUNT与每页集合一致，不能先取一页再丢弃不符餐厅。 */
    @Test
    void shouldCountAndSortAdvancedResultsBeforeLimit() throws Exception {
        advancedFixtures();
        statistics(201, 1, 1, 0, "6", "6", "6", "6", "6");
        statistics(202, 1, 1, 0, "8", "8", "8", "8", "8");
        statistics(203, 1, 1, 0, "9", "9", "9", "9", "9");
        for (int page = 1; page <= 3; page++) {
            mvc.perform(get("/api/restaurants").param("keyword", "高级").param("maxPrice", "50")
                            .param("maxDistanceMeters", "2000").param("sort", "score")
                            .param("pageSize", "1").param("page", Integer.toString(page)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
                    .andExpect(jsonPath("$.data.totalPages").value(3))
                    .andExpect(jsonPath("$.data.records.length()").value(1))
                    .andExpect(jsonPath("$.data.records[0].id").value(Integer.toString(204 - page)));
        }
    }

    /** 新增查询参数也必须经过绑定和校验；不能忽略非法值后意外放宽结果。 */
    @ParameterizedTest
    @CsvSource({"maxPrice,0", "maxPrice,-1", "maxPrice,30.001", "maxPrice,NaN", "maxPrice,100000000",
            "maxDistanceMeters,0", "maxDistanceMeters,-1", "maxDistanceMeters,1.5",
            "maxDistanceMeters,NaN", "maxDistanceMeters,20040001"})
    void shouldRejectInvalidAdvancedFilterValues(String parameter, String value) throws Exception {
        mvc.perform(get("/api/restaurants").param(parameter, value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
    }

    /** 夹具仅存在于本测试创建的独立库，包含临界价格、近/远位置、停业及两种未知值。 */
    private void advancedFixtures() {
        insert(201, "高级A三十元", "HOT_POT", 30, "EXACT", null, 30.7544, 103.9312, "OPERATING");
        insert(202, "高级B三十一元", "HOT_POT", 31, "ESTIMATE", null, 30.7624, 103.9312, "OPERATING");
        insert(203, "高级C五十元停业", "HOT_POT", 50, "EXACT", null, 30.7694, 103.9312, "CLOSED");
        insert(204, "高级D远处二十元", "OTHER", 20, "EXACT", null, 30.7794, 103.9312, "OPERATING");
        insert(205, "高级E未知人均", "HOT_POT", null, "UNKNOWN", null, 30.7584, 103.9312, "OPERATING");
        insert(206, "高级F待补位置", "HOT_POT", 20, "EXACT", null, null, null, "OPERATING");
    }

    /** 地图不使用目录页码截断，边界包含且与区域列表共享完整筛选。 */
    @Test
    void shouldReadViewportPointsIndependentlyOfPagination() throws Exception {
        mvc.perform(get("/api/restaurants/map-points").param("west","103.93").param("east","103.94")
                .param("south","30.7544").param("north","30.7644").param("pageSize","1").param("page","99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.matchedCount").value(2))
                .andExpect(jsonPath("$.data.returnedCount").value(2)).andExpect(jsonPath("$.data.points[0].id").value("101"))
                .andExpect(jsonPath("$.data.truncated").value(false)).andExpect(jsonPath("$.data.points[0].createdBy").doesNotExist());
        mvc.perform(get("/api/restaurants").param("west","103.93").param("east","103.94")
                .param("south","30.7544").param("north","30.7644").param("pageSize","1").param("page","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value("102"));
    }

    /** 数量上限之前筛选和稳定排序，不把501个有效点位称为500个。 */
    @Test
    void shouldExposePointTruncationAndApplyCombinedFilters() throws Exception {
        for(int i=0;i<501;i++) insert(10000+i,"地图"+i,"HOT_POT",30,"EXACT",null,30.7,104.1,"OPERATING");
        mvc.perform(get("/api/restaurants/map-points").param("west","104").param("east","104.2")
                .param("south","30.6").param("north","30.8").param("category","HOT_POT").param("maxPrice","30"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.matchedCount").value(501))
                .andExpect(jsonPath("$.data.points.length()").value(500)).andExpect(jsonPath("$.data.truncated").value(true));
        mvc.perform(get("/api/restaurants/map-points").param("west","104").param("east","104.2")
                .param("south","30.6").param("north","30.8").param("maxPrice","29"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.matchedCount").value(0));
    }

    /** 缺边、反向、非数值及越界参数均拒绝，不能忽略后返回全城数据。 */
    @ParameterizedTest
    @CsvSource({"west,NaN", "west,181", "east,103", "south,31", "north,-91"})
    void shouldRejectInvalidViewport(String field,String value) throws Exception {
        var params = new org.springframework.util.LinkedMultiValueMap<String,String>();
        params.add("west","104");params.add("east","105");params.add("south","30");params.add("north","31");
        params.set(field,value);
        mvc.perform(get("/api/restaurants/map-points").params(params)).andExpect(status().isBadRequest());
    }

    @Test
    void shouldRequireCompleteMapBoundsAndKeepGlobalCatalogCompatible() throws Exception {
        mvc.perform(get("/api/restaurants/map-points")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/restaurants").param("west","104")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/restaurants")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(7));
    }

    private void assertAdvanced(String price, String meters, String category, boolean hideClosed, String... ids) throws Exception {
        var request = get("/api/restaurants").param("keyword", "高级").param("hideClosed", Boolean.toString(hideClosed));
        if (price != null) request.param("maxPrice", price);
        if (meters != null) request.param("maxDistanceMeters", meters);
        if (category != null) request.param("category", category);
        var result = mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(ids.length))
                .andExpect(jsonPath("$.data.records.length()").value(ids.length));
        for (int index = 0; index < ids.length; index++) {
            result.andExpect(jsonPath("$.data.records[" + index + "].id").value(ids[index]));
        }
    }

    /** 人工构造统计读模型验证查询边界，真实发布到统计再到公开读取由review写入集成测试覆盖。 */
    private void statistics(long id, long reviews, long participants, long recommended, String overall,
                            String taste, String value, String service, String ambience) {
        jdbc.update("INSERT INTO restaurant_review_statistics(restaurant_id,review_count,participant_count,recommend_count,"
                        + "overall_score_sum,taste_sum,value_sum,service_sum,ambience_sum) VALUES(?,?,?,?,?,?,?,?,?)",
                id, reviews, participants, recommended, overall, taste, value, service, ambience);
    }

    private void insert(long id, String name, String category, Integer price, String kind, String address,
                        Double latitude, Double longitude, String businessStatus) {
        jdbc.update("INSERT INTO restaurants(id,name,category_code,price_per_person,price_kind,address,latitude,longitude,"
                        + "needs_location,business_status,source_type) VALUES (?,?,?,?,?,?,?,?,?,?,'LEGACY_IMPORT')",
                id, name, category, price, kind, address, latitude, longitude, latitude == null, businessStatus);
    }

    /** 必须显式指定回环端口，不能误用业务URL或从开发配置读取数据库凭据。 */
    private static String server() {
        String server = System.getenv().getOrDefault("RESTAURANT_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Restaurant API tests require an explicit loopback MySQL server");
        }
        return server;
    }

    private static String username() {
        return System.getenv().getOrDefault("RESTAURANT_TEST_DB_USERNAME", "root");
    }

    private static String password() {
        String password = System.getenv("RESTAURANT_TEST_DB_PASSWORD");
        if (password == null) throw new IllegalStateException("Set RESTAURANT_TEST_DB_PASSWORD for isolated tests");
        return password;
    }

    private static String url(String server, String name) {
        return server + name + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"
                + "&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(server(), name), username(), password());
    }
}
