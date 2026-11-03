package com.ginkgo.foodmap.restaurant;

import com.ginkgo.foodmap.restaurant.mapper.RestaurantWriteMapper;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实MySQL/Flyway、RS256 JWT、安全过滤链与写入事务集成测试，所有HTTP操作独立提交。
 * 仅显式restaurant.integration开启，专用回环随机库及内存RSA，不读取业务配置、不发送邮件。
 */
@EnabledIfSystemProperty(named = "restaurant.integration", matches = "true")
@ActiveProfiles("test")
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "app.file.oss.bucket=restaurant-album-test",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.minimum-idle=0", "spring.datasource.hikari.maximum-pool-size=6"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
class RestaurantWriteApiIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtEncoder encoder;
    @Autowired private AuthenticationProperties authentication;
    @MockitoBean private LoginSessionService sessions;
    @MockitoBean private com.ginkgo.foodmap.file.storage.OssStorageClient storage;
    @MockitoSpyBean private RestaurantWriteMapper mapper;
    @MockitoSpyBean private com.ginkgo.foodmap.restaurant.mapper.RestaurantRequestReviewMapper reviewMapper;
    @MockitoSpyBean private com.ginkgo.foodmap.restaurant.mapper.RestaurantPhotoMapper photoMapper;
    private String owner;
    private String other;
    private String admin;
    private long albumFileSequence;

    /** 独立建库后让真实应用Flyway执行全部迁移，与只读测试各自隔离。 */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_restaurant_write_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connect(""); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", RestaurantWriteApiIntegrationTest::username);
        registry.add("spring.datasource.password", RestaurantWriteApiIntegrationTest::password);
    }

    /** 测试夹具只放入测试账号，真实认证转换器仍从数据库读取当前状态与角色。 */
    @BeforeEach
    void prepare() {
        reset(mapper, reviewMapper, photoMapper, sessions, storage);
        albumFileSequence = 1000;
        jdbc.update("DELETE FROM restaurant_photos");
        jdbc.update("DELETE FROM restaurant_change_photos");
        jdbc.update("DELETE FROM restaurant_change_requests");
        jdbc.update("DELETE FROM media_files");
        jdbc.update("DELETE FROM restaurant_tags");
        jdbc.update("DELETE FROM restaurants");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
        jdbc.update("UPDATE restaurant_categories SET enabled=TRUE");
        for (long id : List.of(11L, 22L, 33L)) {
            jdbc.update("INSERT INTO users(id,username,email,password_hash,email_verified) VALUES (?,?,?,?,TRUE)",
                    id, "fixture_" + id, "fixture" + id + "@example.test", "test-only-unused-hash");
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ROLE_USER'", id);
        }
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT 33,id FROM roles WHERE code='ROLE_ADMIN'");
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(true);
        owner = token(11, 0, false);
        other = token(22, 0, true); // 故意在JWT中伪报管理员，真实权限只取数据库。
        admin = token(33, 0, false);
    }

    /** 只清理实际创建并严格匹配的随机库，绝不使用Flyway clean。 */
    @AfterAll
    static void dropDatabase() throws SQLException {
        if (!created) return;
        if (!database.matches("ginkgo_restaurant_write_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Refusing to remove an unexpected database name");
        }
        try (Connection connection = connect(""); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    /** 创建、查询、完整修改、软删除和管理员恢复形成真实持久化闭环，标签历史没有被抹除。 */
    @Test
    void shouldCompleteCrudAndRestoreWithoutLosingHistory() throws Exception {
        var input = valid();
        input.put("name", "　测试餐厅 \t");
        input.put("address", "  测试地址　");
        input.put("tags", List.of(" 招牌 ", "咖啡", "招牌", "CAFE", "café"));
        var created = create(input);
        String id = created.get("restaurant").get("id").asString();
        assertThat(created.has("rowVersion")).isFalse();
        assertThat(created.get("restaurant").get("name").asString()).isEqualTo("测试餐厅");
        assertThat(created.get("restaurant").get("address").asString()).isEqualTo("测试地址");
        assertThat(tags(created.get("restaurant"))).containsExactly("招牌", "咖啡", "CAFE");
        assertThat(jdbc.queryForObject("SELECT created_by FROM restaurants WHERE id=?", Long.class, id)).isEqualTo(33);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM restaurants WHERE id=?", Long.class, id)).isEqualTo(33);
        assertThat(created.get("restaurant").has("sourceType")).isFalse();
        assertThat(created.get("restaurant").has("coordinateSystem")).isFalse();
        mvc.perform(get("/api/restaurants/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").isEmpty()).andExpect(jsonPath("$.data.reviewCount").value(0));
        perform(get("/api/restaurants/" + id + "/management"), admin, null)
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist());

        input.put("name", "修改后");
        input.put("businessStatus", "CLOSED"); input.put("tags", List.of("新标签", "第二项"));
        perform(put("/api/restaurants/" + id), admin, input).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist());
        mvc.perform(get("/api/restaurants/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.tags[0]").value("新标签"));
        perform(delete("/api/restaurants/" + id), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rowVersion").doesNotExist())
                .andExpect(jsonPath("$.data.deleted").value(true));
        mvc.perform(get("/api/restaurants/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/restaurants")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_tags WHERE restaurant_id=?", Long.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT delete_reason_code FROM restaurants WHERE id=?", String.class, id)).isEqualTo("ADMIN_REMOVAL");
        assertThat(jdbc.queryForObject("SELECT deleted_by FROM restaurants WHERE id=?", Long.class, id)).isEqualTo(33);
        perform(get("/api/restaurants/" + id + "/management"), admin, null).andExpect(status().isOk());
        perform(get("/api/restaurants/" + id + "/management"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist());
        perform(post("/api/restaurants/" + id + "/restore"), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rowVersion").doesNotExist())
                .andExpect(jsonPath("$.data.deleted").value(false));
        mvc.perform(get("/api/restaurants/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags[1]").value("第二项"))
                .andExpect(jsonPath("$.data.businessStatus").value("CLOSED"));
        assertThat(jdbc.queryForMap("SELECT deleted_at,deleted_by,delete_reason_code FROM restaurants WHERE id=?", id).values())
                .allMatch(Objects::isNull);
        assertThat(jdbc.queryForObject("SELECT created_by FROM restaurants WHERE id=?", Long.class, id)).isEqualTo(33);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM restaurants WHERE id=?", Long.class, id)).isEqualTo(33);
    }

    /** 普通账号即使JWT伪报角色也不能读取私有资料、改删他人餐厅或恢复删除记录。 */
    @Test
    void shouldEnforceOwnerAndDatabaseAdministratorRole() throws Exception {
        String id = create(valid()).get("restaurant").get("id").asString();
        var update = valid();
        perform(get("/api/restaurants/" + id + "/management"), other, null).andExpect(status().isForbidden());
        perform(put("/api/restaurants/" + id), other, update).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        perform(delete("/api/restaurants/" + id), other, null).andExpect(status().isForbidden());
        perform(post("/api/restaurants/" + id + "/restore"), owner, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        perform(put("/api/restaurants/" + id), admin, update).andExpect(status().isOk());
        perform(delete("/api/restaurants/" + id), admin, null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT delete_reason_code FROM restaurants WHERE id=?", String.class, id)).isEqualTo("ADMIN_REMOVAL");
        jdbc.update("DELETE FROM user_roles WHERE user_id=33 AND role_id=(SELECT id FROM roles WHERE code='ROLE_ADMIN')");
        perform(post("/api/restaurants/" + id + "/restore"), admin, null)
                .andExpect(status().isForbidden());
    }

    /** 历史未知归属不按昵称或登录自动认领，管理员纠错也不改变原创建者。 */
    @Test
    void shouldKeepUnknownOwnershipDuringAdminCorrection() throws Exception {
        jdbc.update("INSERT INTO restaurants(id,name,category_code) VALUES(101,'历史夹具','OTHER')");
        var update = valid();
        perform(put("/api/restaurants/101"), owner, update).andExpect(status().isForbidden());
        perform(put("/api/restaurants/101"), admin, update).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT created_by FROM restaurants WHERE id=101", Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT needs_location FROM restaurants WHERE id=101", Boolean.class)).isFalse();
    }

    /** 旧页面仍可覆盖之前保存的资料；重复删除/恢复只按当前删除状态明确失败。 */
    @Test
    void shouldAllowLaterSaveAndKeepRepeatedStateTransitionsStable() throws Exception {
        var stalePage = valid(); stalePage.put("priceKind", "EXACT"); stalePage.put("pricePerPerson", 20);
        String id = create(stalePage).get("restaurant").get("id").asString();
        var newer = valid(); newer.put("priceKind", "EXACT"); newer.put("pricePerPerson", 30);
        perform(put("/api/restaurants/" + id), admin, newer).andExpect(status().isOk());
        stalePage.put("name", "旧页面后保存");
        perform(put("/api/restaurants/" + id), admin, stalePage).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurant.pricePerPerson").value(20))
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist());
        perform(put("/api/restaurants/" + id), admin, stalePage).andExpect(status().isOk());
        perform(delete("/api/restaurants/" + id), admin, null).andExpect(status().isOk());
        perform(delete("/api/restaurants/" + id), admin, null).andExpect(status().isConflict());
        perform(delete("/api/restaurants/" + id), admin, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTAURANT_STATE_CONFLICT"));
        perform(post("/api/restaurants/" + id + "/restore"), admin, null).andExpect(status().isOk());
        perform(post("/api/restaurants/" + id + "/restore"), admin, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTAURANT_STATE_CONFLICT"));
    }

    /** 未登录、过期、撤销、停用、凭证版本变化和认证依赖故障均不能写入。 */
    @Test
    void shouldRejectInvalidAuthenticationWithoutWriting() throws Exception {
        mvc.perform(post("/api/restaurants").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/restaurants/1/management")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/restaurants/1")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/restaurants/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/restaurants/1/restore")).andExpect(status().isUnauthorized());
        perform(post("/api/restaurants"), "invalid", valid()).andExpect(status().isUnauthorized());
        perform(post("/api/restaurants"), token(11, 0, false, true), valid()).andExpect(status().isUnauthorized());
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(false);
        perform(post("/api/restaurants"), owner, valid()).andExpect(status().isUnauthorized());
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(true);
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=11");
        perform(post("/api/restaurants"), owner, valid()).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status='ACTIVE',credential_version=1 WHERE id=11");
        perform(post("/api/restaurants"), owner, valid()).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET credential_version=0 WHERE id=11");
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenThrow(new RedisConnectionFailureException("test-only"));
        perform(post("/api/restaurants"), owner, valid()).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isZero();
    }

    /** 禁止传入身份、来源、评分、版本等越权字段，拼错字段也不默默返回保存成功。 */
    @ParameterizedTest
    @ValueSource(strings = {"createdBy", "updatedBy", "sourceType", "id", "rowVersion", "score", "tier",
            "coordinateSystem", "reviewCount", "deletedAt", "deletedBy", "needsLocation", "administrator", "unknownField"})
    void shouldRejectUnknownAndPrivilegedCreationFields(String field) throws Exception {
        var input = valid(); input.put(field, "forged");
        perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_MALFORMED_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isZero();
    }

    /** 字段级空值/编码问题在DTO校验拒绝，金额/坐标精度与组合规则在服务拒绝。 */
    @ParameterizedTest
    @CsvSource({"name,''", "categoryCode,other", "categoryCode,OTHER ", "priceKind,unknown",
            "businessStatus,OPEN"})
    void shouldRejectInvalidCodesAndRequiredText(String field, String value) throws Exception {
        var input = valid(); input.put(field, value.equals("OTHER") ? "OTHER " : value);
        perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest());
    }

    /** 拒绝超范围/非整数金额、价格类型矛盾、未确认位置及不成对坐标。 */
    @Test
    void shouldValidatePriceAndLocationCombinations() throws Exception {
        for (Object price : List.of(0, 5001, -1, 15.5)) {
            var input = valid(); input.put("priceKind", "EXACT"); input.put("pricePerPerson", price);
            perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest());
        }
        var input = valid(); input.put("pricePerPerson", 20);
        perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest());
        input = valid(); input.put("priceKind", "ESTIMATE");
        perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest());
        for (String field : List.of("latitude", "longitude", "locationConfirmed", "name", "categoryCode", "tags")) {
            input = valid(); input.put(field, null);
            perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest());
        }
        for (double latitude : List.of(2.99, 54.01, 30.1234567)) {
            input = valid(); input.put("latitude", latitude);
            perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest());
        }
        for (double longitude : List.of(72.99, 136.01, 103.1234567)) {
            input = valid(); input.put("longitude", longitude);
            perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest());
        }
        input = valid(); input.put("locationConfirmed", false);
        perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isZero();
    }

    /** 名称/地址按Unicode码点计数，emoji边界与中文一致；空白地址存NULL。 */
    @Test
    void shouldRespectUnicodeAndPriceBoundaries() throws Exception {
        var input = valid(); input.put("name", "🍜".repeat(40)); input.put("address", "🍜".repeat(80));
        input.put("priceKind", "EXACT"); input.put("pricePerPerson", 1); input.put("latitude", 3); input.put("longitude", 73);
        create(input);
        input.put("name", "🍜".repeat(41));
        perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest());
        input.put("name", "边界夹具"); input.put("address", "🍜".repeat(81));
        perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest());
        input.put("address", "　 \t"); input.put("priceKind", "ESTIMATE"); input.put("pricePerPerson", 5000);
        input.put("latitude", 54); input.put("longitude", 136);
        var result = create(input).get("restaurant");
        assertThat(result.get("address").isNull()).isTrue();
        assertThat(result.get("pricePerPerson").asInt()).isEqualTo(5000);
        assertThat(result.get("priceKind").asString()).isEqualTo("ESTIMATE");
    }

    /** 标签总长/数量由事务前置校验，数据库真实排序规则去重且不丢首项顺序。 */
    @Test
    void shouldBoundAndNormalizeTags() throws Exception {
        var input = valid(); input.put("tags", List.of("甲".repeat(40), "乙".repeat(40)));
        create(input);
        input.put("tags", List.of("甲".repeat(40), "乙".repeat(41)));
        perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESTAURANT_INVALID_INPUT"));
        input.put("tags", List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"));
        perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest());
        input.put("tags", List.of(" "));
        perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest());
        input.put("tags", Arrays.asList("正常", null));
        perform(post("/api/restaurants"), admin, input).andExpect(status().isBadRequest());
        input.put("tags", List.of("🍜".repeat(40), "🍚".repeat(40)));
        create(input);
        input.put("tags", List.of("café", "CAFE", "后项"));
        assertThat(tags(create(input).get("restaurant"))).containsExactly("café", "后项");
    }

    /** 允许同名分店；停用分类可保留原值，但不用于新建或切换。 */
    @Test
    void shouldPreserveDisabledCategoryButRejectNewAssignments() throws Exception {
        String id = create(valid()).get("restaurant").get("id").asString();
        create(valid());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isEqualTo(2);
        jdbc.update("UPDATE restaurant_categories SET enabled=FALSE WHERE code='OTHER'");
        perform(post("/api/restaurants"), admin, valid()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESTAURANT_CATEGORY_UNAVAILABLE"));
        var input = valid();
        perform(put("/api/restaurants/" + id), admin, input).andExpect(status().isOk());
 input.put("categoryCode", "SICHUAN");
        perform(put("/api/restaurants/" + id), admin, input).andExpect(status().isOk());
 input.put("categoryCode", "OTHER");
        perform(put("/api/restaurants/" + id), admin, input).andExpect(status().isBadRequest());
        var absent = valid(); absent.put("categoryCode", "NON_EXISTENT");
        perform(post("/api/restaurants"), admin, absent).andExpect(status().isBadRequest());
    }

    /** 标签保存失败后主表新内容与旧标签全部回滚；创建也不会留下无标签的半成品。 */
    @Test
    void shouldRollbackWholeWriteWhenTagPersistenceFails() throws Exception {
        String id = create(valid()).get("restaurant").get("id").asString();
        doThrow(new IllegalStateException("test-only injected tag failure")).when(mapper).insertTags(anyLong(), anyList());
        var update = valid(); update.put("name", "不应保存"); update.put("tags", List.of("新"));
        perform(put("/api/restaurants/" + id), admin, update).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT name FROM restaurants WHERE id=?", String.class, id)).isEqualTo("CRUD测试夹具");
        assertThat(jdbc.queryForList("SELECT tag FROM restaurant_tags WHERE restaurant_id=? ORDER BY position", String.class, id))
                .containsExactly("原标签");
        perform(post("/api/restaurants"), admin, valid()).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isEqualTo(1);
    }

    /** 并发编辑都可成功，最终资料及整组标签来自最后一个写入事务，不能交叉混合。 */
    @Test
    void shouldAllowBothConcurrentUpdatesAndKeepWholeTagSet() throws Exception {
        String id = create(valid()).get("restaurant").get("id").asString();
        var first = valid(); first.put("name", "并发甲"); first.put("tags", List.of("甲1", "甲2"));
        var second = valid(); second.put("name", "并发乙"); second.put("tags", List.of("乙1", "乙2"));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return perform(put("/api/restaurants/" + id), admin, first).andReturn(); });
            var b = executor.submit(() -> { start.await(); return perform(put("/api/restaurants/" + id), admin, second).andReturn(); });
            start.countDown();
            var results = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().map(result -> result.getResponse().getStatus())).containsExactly(200, 200);
        }
        String name = jdbc.queryForObject("SELECT name FROM restaurants WHERE id=?", String.class, id);
        assertThat(name).isIn("并发甲", "并发乙");
        assertThat(jdbc.queryForList("SELECT tag FROM restaurant_tags WHERE restaurant_id=? ORDER BY position", String.class, id))
                .containsExactly(name.equals("并发甲") ? "甲1" : "乙1", name.equals("并发甲") ? "甲2" : "乙2");
    }

    /** 删除与编辑串行执行：先编辑可随后删除，先删除则后续编辑404，不能被PUT意外恢复。 */
    @Test
    void shouldSerializeConcurrentUpdateAndDelete() throws Exception {
        String id = create(valid()).get("restaurant").get("id").asString();
        var update = valid();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return perform(put("/api/restaurants/" + id), admin, update).andReturn(); });
            var b = executor.submit(() -> { start.await(); return perform(delete("/api/restaurants/" + id), admin, null).andReturn(); });
            start.countDown();
            int editing = a.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            int deleting = b.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            assertThat(deleting).isEqualTo(200);
            assertThat(editing).isIn(200, 409);
        }
        mvc.perform(get("/api/restaurants/" + id)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_tags WHERE restaurant_id=?", Long.class, id)).isEqualTo(1);
    }

    /** 编辑/删除/恢复均无需版本，资源ID仍校验，已删除记录不能通过普通编辑复活。 */
    @Test
    void shouldAcceptVersionlessRequestsAndValidateResourceIdentity() throws Exception {
        String id = create(valid()).get("restaurant").get("id").asString();
        perform(put("/api/restaurants/" + id), admin, valid()).andExpect(status().isOk());
        perform(get("/api/restaurants/01/management"), admin, null).andExpect(status().isBadRequest());
        perform(get("/api/restaurants/999999/management"), admin, null).andExpect(status().isNotFound());
        perform(delete("/api/restaurants/" + id), admin, null).andExpect(status().isOk());
        perform(put("/api/restaurants/" + id), admin, valid()).andExpect(status().isConflict());
        perform(put("/api/restaurants/" + id), admin, valid()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTAURANT_STATE_CONFLICT"));
        perform(post("/api/restaurants/" + id + "/restore"), admin, null).andExpect(status().isOk());
    }

    /** PUT整体替换可清空标签/金额，来源不可注入；无效标签更新不能改变旧内容。 */
    @Test
    void shouldReplaceAllEditableFieldsWithoutAcceptingInternalOnes() throws Exception {
        var original = valid(); original.put("priceKind", "EXACT"); original.put("pricePerPerson", 30);
        String id = create(original).get("restaurant").get("id").asString();
        var update = valid(); update.put("sourceType", "LEGACY_IMPORT");
        perform(put("/api/restaurants/" + id), admin, update).andExpect(status().isBadRequest());
        update.remove("sourceType"); update.put("tags", List.of("甲".repeat(40), "乙".repeat(41)));
        perform(put("/api/restaurants/" + id), admin, update).andExpect(status().isBadRequest());
        update.put("tags", List.of());
        perform(put("/api/restaurants/" + id), admin, update).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurant.tags").isEmpty())
                .andExpect(jsonPath("$.data.restaurant.pricePerPerson").isEmpty())
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_tags WHERE restaurant_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT created_by FROM restaurants WHERE id=?", Long.class, id)).isEqualTo(33);
    }

    /** 管理响应和公开响应均不返回版本；旧版客户端提交rowVersion按未知JSON字段拒绝。 */
    @Test
    void shouldRemoveVersionFromManagementContract() throws Exception {
        String id = create(valid()).get("restaurant").get("id").asString();
        perform(get("/api/restaurants/" + id + "/management"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist())
                .andExpect(jsonPath("$.data.restaurant.createdBy").doesNotExist());
        mvc.perform(get("/api/restaurants/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist())
                .andExpect(jsonPath("$.data.createdBy").doesNotExist());
        var obsolete = valid(); obsolete.put("rowVersion", "0");
        perform(put("/api/restaurants/" + id), admin, obsolete).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_MALFORMED_REQUEST"));
        perform(delete("/api/restaurants/" + id), admin, null).andExpect(status().isOk());
    }

    /** 非创建者可提交完整待审申请，但不能改变正式资料；重试防重、伪造字段与无效位置均拒绝。 */
    @Test
    void shouldSubmitCorrectionWithoutChangingPublicRestaurant() throws Exception {
        String id = create(valid()).get("restaurant").get("id").asString();
        var input = valid();
        input.put("clientRequestId", UUID.randomUUID().toString());
        input.put("name", " 待审核新店名 ");
        input.put("restaurantType", "CANTEEN");
        input.put("supportsDineIn", true);
        input.put("supportsDelivery", false);
        input.put("priceKind", "EXACT"); input.put("pricePerPerson",25);
        String endpoint = "/api/restaurants/" + id + "/change-requests";
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isUnauthorized());
        var result = perform(post(endpoint), other, input).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING")).andReturn();
        String application = json.readTree(result.getResponse().getContentAsString()).get("data").get("id").asString();
        perform(post(endpoint), other, input).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(application));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_change_requests", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT submitted_by FROM restaurant_change_requests WHERE id=?", Long.class, application)).isEqualTo(22);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(proposed_data,'$.name')) FROM restaurant_change_requests WHERE id=?", String.class, application)).isEqualTo("待审核新店名");
        assertThat(jdbc.queryForObject("SELECT name FROM restaurants WHERE id=?", String.class, id)).isEqualTo("CRUD测试夹具");
        assertThat(jdbc.queryForObject("SELECT price_per_person FROM restaurants WHERE id=?", Integer.class, id)).isNull();
        input.put("name","不同申请内容");
        perform(post(endpoint), other, input).andExpect(status().isConflict());
        input.put("clientRequestId",UUID.randomUUID().toString()); input.put("submittedBy",11);
        perform(post(endpoint), other, input).andExpect(status().isBadRequest());
        input.remove("submittedBy"); input.put("latitude",99);
        perform(post(endpoint), other, input).andExpect(status().isBadRequest());
        input.put("latitude",30.7544); input.put("campusCode","SHAHE");
        perform(post(endpoint), other, input).andExpect(status().isBadRequest());
        input.remove("campusCode");
        perform(post("/api/restaurants/999999/change-requests"), other, input).andExpect(status().isNotFound());
    }

    /** 新店投稿只产生待审快照，不生成餐厅、标签或评分；匿名与伪造状态均拒绝。 */
    @Test
    void shouldSubmitNewRestaurantWithoutPublishing() throws Exception {
        var input = valid(); input.put("clientRequestId", UUID.randomUUID().toString());
        String endpoint = "/api/restaurant-submissions";
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isUnauthorized());
        var result = perform(post(endpoint), other, input).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("PENDING")).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("data").get("id").asString();
        perform(post(endpoint), other, input).andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_change_requests", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT restaurant_id FROM restaurant_change_requests WHERE id=?", Long.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT submitted_by FROM restaurant_change_requests WHERE id=?", Long.class, id)).isEqualTo(22);
        for (String table : List.of("restaurants", "restaurant_tags", "restaurant_review_statistics")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isZero();
        }
        input.put("name", "不同内容");
        perform(post(endpoint), other, input).andExpect(status().isConflict());
        input.put("clientRequestId", UUID.randomUUID().toString()); input.put("status", "APPROVED");
        perform(post(endpoint), other, input).andExpect(status().isBadRequest());
        input.remove("status"); input.put("locationConfirmed", false);
        perform(post(endpoint), other, input).andExpect(status().isBadRequest());
        input.put("locationConfirmed", true);
        jdbc.update("UPDATE restaurant_categories SET enabled=FALSE WHERE code='OTHER'");
        perform(post(endpoint), other, input).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_change_requests", Long.class)).isEqualTo(1);
    }

    /** 新店没有目标行锁，使用唯一键原子防重；两个首次并发请求获得相同回执。 */
    @Test
    void shouldDeduplicateConcurrentNewSubmissions() throws Exception {
        var input = valid(); input.put("clientRequestId", UUID.randomUUID().toString());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return perform(post("/api/restaurant-submissions"), other, input).andReturn(); });
            var b = executor.submit(() -> { start.await(); return perform(post("/api/restaurant-submissions"), other, input).andReturn(); });
            start.countDown();
            var first = a.get(15, TimeUnit.SECONDS); var second = b.get(15, TimeUnit.SECONDS);
            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            assertThat(second.getResponse().getStatus()).isEqualTo(201);
            assertThat(json.readTree(first.getResponse().getContentAsString()).get("data").get("id").asString())
                    .isEqualTo(json.readTree(second.getResponse().getContentAsString()).get("data").get("id").asString());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_change_requests", Long.class)).isEqualTo(1);
    }

    /** 同账号跨新建和纠错共用请求号命名空间，不能把新店回执误作已有商户的纠错回执。 */
    @Test
    void shouldRejectReusingSubmissionIdentityForCorrection() throws Exception {
        String target = create(valid()).get("restaurant").get("id").asString();
        var input = valid(); input.put("clientRequestId", UUID.randomUUID().toString());
        perform(post("/api/restaurant-submissions"), other, input).andExpect(status().isCreated());
        perform(post("/api/restaurants/" + target + "/change-requests"), other, input).andExpect(status().isConflict());
        input.put("clientRequestId", UUID.randomUUID().toString());
        perform(post("/api/restaurants/" + target + "/change-requests"), other, input).andExpect(status().isCreated());
        perform(post("/api/restaurant-submissions"), other, input).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_change_requests", Long.class)).isEqualTo(2);
    }

    /** 只模拟审核落库状态以验证V17兼容；本测试不代表已实现审核业务接口。 */
    @Test
    void shouldReplayPublishedCreateWithoutComparingItsGeneratedRestaurantId() throws Exception {
        var input = valid(); input.put("clientRequestId", UUID.randomUUID().toString());
        String endpoint = "/api/restaurant-submissions";
        var first = perform(post(endpoint), other, input).andExpect(status().isCreated()).andReturn();
        String requestId = json.readTree(first.getResponse().getContentAsString()).get("data").get("id").asString();
        assertThat(jdbc.queryForObject("SELECT request_type FROM restaurant_change_requests WHERE id=?", String.class, requestId)).isEqualTo("CREATE");
        jdbc.update("INSERT INTO restaurants(id,name,category_code) VALUES(101,'发布结果夹具','OTHER')");
        jdbc.update("UPDATE restaurant_change_requests SET status='APPROVED',restaurant_id=101,reviewed_by=33,reviewed_at=CURRENT_TIMESTAMP(6),approved_data=JSON_OBJECT('name','管理员修订') WHERE id=?", requestId);
        perform(post(endpoint), other, input).andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(requestId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_change_requests", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT restaurant_id FROM restaurant_change_requests WHERE id=?", Long.class, requestId)).isEqualTo(101);
        assertThat(jdbc.queryForObject("SELECT status FROM restaurant_change_requests WHERE id=?", String.class, requestId)).isEqualTo("APPROVED");
        input.put("name", "用户修改了原请求");
        perform(post(endpoint), other, input).andExpect(status().isConflict());
        input.put("name", valid().get("name"));
        perform(post("/api/restaurants/101/change-requests"), other, input).andExpect(status().isConflict());
        input.put("clientRequestId", UUID.randomUUID().toString()); input.put("requestType", "CREATE");
        perform(post(endpoint), other, input).andExpect(status().isBadRequest());
    }

    /** 管理查询必须服务端授权；分页、文字转义和快照读取不能改变申请或公开商户。 */
    @Test
    void shouldReadAdministratorQueueAndCompareRealApplicationDetails() throws Exception {
        String endpoint = "/api/admin/restaurant-requests";
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint).header("Authorization", "Bearer " + other)).andExpect(status().isForbidden());
        String target = create(valid()).get("restaurant").get("id").asString();
        var input = valid(); input.put("clientRequestId", UUID.randomUUID().toString()); input.put("name", "100%_小店");
        String createdId = json.readTree(perform(post("/api/restaurant-submissions"), other, input).andReturn().getResponse().getContentAsString()).get("data").get("id").asString();
        input.put("clientRequestId", UUID.randomUUID().toString()); input.put("name", "普通投稿");
        perform(post("/api/restaurant-submissions"), other, input).andExpect(status().isCreated());
        input.put("clientRequestId", UUID.randomUUID().toString()); input.put("name", "纠错后标题");
        String updateId = json.readTree(perform(post("/api/restaurants/" + target + "/change-requests"), other, input).andReturn().getResponse().getContentAsString()).get("data").get("id").asString();
        mvc.perform(get(endpoint).param("pageSize", "2").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(2)).andExpect(jsonPath("$.data.counts.PENDING").value(3))
                .andExpect(jsonPath("$.data.records[0].submitterName").value("fixture_22"));
        mvc.perform(get(endpoint).param("keyword", "%_").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get(endpoint).param("keyword", updateId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].id").value(updateId));
        mvc.perform(get(endpoint).param("status", "APPROVED").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        for (String[] invalid : List.of(new String[]{"status", "OTHER"}, new String[]{"page", "0"}, new String[]{"pageSize", "51"}))
            mvc.perform(get(endpoint).param(invalid[0], invalid[1]).header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
        mvc.perform(get(endpoint + "/" + createdId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.application.requestType").value("CREATE"))
                .andExpect(jsonPath("$.data.current").isEmpty()).andExpect(jsonPath("$.data.proposed.name").value("100%_小店"))
                .andExpect(jsonPath("$.data.proposed.clientRequestId").doesNotExist()).andExpect(jsonPath("$.data.application.requestHash").doesNotExist());
        // 历史快照中的旧字段不能导致当前DTO反序列化失败，也不能被输出为新的可编辑资料。
        jdbc.update("UPDATE restaurant_change_requests SET proposed_data=JSON_SET(proposed_data,'$.campusCode','OLD') WHERE id=?", updateId);
        jdbc.update("UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),deleted_by=33,delete_reason_code='ADMIN_REMOVAL' WHERE id=?", target);
        mvc.perform(get(endpoint + "/" + updateId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.targetDeleted").value(true))
                .andExpect(jsonPath("$.data.current.name").value(valid().get("name")))
                .andExpect(jsonPath("$.data.proposed.name").value("纠错后标题")).andExpect(jsonPath("$.data.proposed.campusCode").doesNotExist());
        mvc.perform(get(endpoint + "/999999").header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESTAURANT_REQUEST_NOT_FOUND"));
        mvc.perform(get(endpoint + "/abc").header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_change_requests WHERE status='PENDING'", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isEqualTo(1);
    }

    /** 私有图片按申请/位置读取；越权、错误用途/归属/状态不得读取云存储，云失败返回503。 */
    @Test
    void shouldReadOnlyBoundRestaurantPhotosForAdministrators() throws Exception {
        var input = valid(); input.put("clientRequestId", UUID.randomUUID().toString());
        String id = json.readTree(perform(post("/api/restaurant-submissions"), other, input).andReturn().getResponse().getContentAsString()).get("data").get("id").asString();
        jdbc.update("INSERT INTO media_files(id,uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,purpose,bound_at) VALUES(901,22,'test-bucket','restaurant-images/private.jpg','image/jpeg',4,1,1,UNHEX(SHA2('photo',256)),'BOUND','RESTAURANT',CURRENT_TIMESTAMP(6))");
        jdbc.update("INSERT INTO restaurant_change_photos(request_id,position,media_file_id) VALUES(?,0,901)", id);
        String endpoint = "/api/admin/restaurant-requests/" + id + "/photos/0";
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint).header("Authorization", "Bearer " + other)).andExpect(status().isForbidden());
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        when(storage.read("test-bucket", "restaurant-images/private.jpg")).thenReturn(jpeg);
        mvc.perform(get(endpoint).header("Authorization", "Bearer " + admin)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().contentType(MediaType.IMAGE_JPEG)).andExpect(content().bytes(jpeg));
        mvc.perform(get("/api/admin/restaurant-requests/" + id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.photos[0].url").value(endpoint));
        clearInvocations(storage);
        jdbc.update("UPDATE media_files SET purpose='REVIEW' WHERE id=901");
        mvc.perform(get(endpoint).header("Authorization", "Bearer " + admin)).andExpect(status().isNotFound());
        jdbc.update("UPDATE media_files SET purpose='RESTAURANT',uploader_user_id=11 WHERE id=901");
        mvc.perform(get(endpoint).header("Authorization", "Bearer " + admin)).andExpect(status().isNotFound());
        jdbc.update("UPDATE media_files SET uploader_user_id=22,status='TEMPORARY',bound_at=NULL,expires_at=CURRENT_TIMESTAMP+INTERVAL 1 DAY WHERE id=901");
        mvc.perform(get(endpoint).header("Authorization", "Bearer " + admin)).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
        jdbc.update("UPDATE media_files SET status='BOUND',expires_at=NULL,bound_at=CURRENT_TIMESTAMP(6) WHERE id=901");
        when(storage.read(anyString(), anyString())).thenThrow(new com.ginkgo.foodmap.common.exception.BusinessException(com.ginkgo.foodmap.file.exception.FileErrorCode.UNAVAILABLE));
        mvc.perform(get(endpoint).header("Authorization", "Bearer " + admin)).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/admin/restaurant-requests/" + id + "/photos/1").header("Authorization", "Bearer " + admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/restaurant-requests/" + id + "/photos/5").header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
    }

    /** 普通餐厅改食堂必须从用户申请贯穿审核、正式列、前台返回与快照，三态用餐方式不能丢失。 */
    @Test
    void shouldPublishRestaurantTypeAndDiningModesFromCorrection() throws Exception {
        String target = create(valid()).get("restaurant").get("id").asString();
        var submission = valid(); submission.put("clientRequestId", UUID.randomUUID().toString());
        submission.put("restaurantType", "CANTEEN"); submission.put("supportsDineIn", true); submission.put("supportsDelivery", false);
        String id = submitted("/api/restaurants/" + target + "/change-requests", submission);
        mvc.perform(get("/api/restaurants/" + target)).andExpect(status().isOk()).andExpect(jsonPath("$.data.restaurantType").value("REGULAR"));
        var approved = valid(); approved.put("restaurantType", "CANTEEN"); approved.put("supportsDineIn", true); approved.put("supportsDelivery", false);
        String endpoint = "/api/admin/restaurant-requests/" + id + "/approve";
        perform(post(endpoint), admin, approved).andExpect(status().isOk());
        mvc.perform(get("/api/restaurants/" + target)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurantType").value("CANTEEN"))
                .andExpect(jsonPath("$.data.supportsDineIn").value(true)).andExpect(jsonPath("$.data.supportsDelivery").value(false));
        perform(get("/api/admin/restaurant-requests/" + id), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.before.restaurantType").value("REGULAR"))
                .andExpect(jsonPath("$.data.approved.restaurantType").value("CANTEEN"))
                .andExpect(jsonPath("$.data.approved.supportsDineIn").value(true)).andExpect(jsonPath("$.data.approved.supportsDelivery").value(false));
        perform(post(endpoint), admin, approved).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
        approved.put("restaurantType", "REGULAR");
        perform(post(endpoint), admin, approved).andExpect(status().isConflict());
        // 独立的直接维护旧协议不带这三个字段，应保留现有类型和用餐方式。
        perform(put("/api/restaurants/" + target), admin, valid()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurant.restaurantType").value("CANTEEN"));
        var next = valid(); next.put("clientRequestId", UUID.randomUUID().toString()); next.put("restaurantType", "CANTEEN");
        next.put("supportsDineIn", null); next.put("supportsDelivery", true);
        String nextId = submitted("/api/restaurants/" + target + "/change-requests", next);
        approved.put("restaurantType", "CANTEEN"); approved.put("supportsDineIn", null); approved.put("supportsDelivery", true);
        perform(post("/api/admin/restaurant-requests/" + nextId + "/approve"), admin, approved).andExpect(status().isOk());
        mvc.perform(get("/api/restaurants/" + target)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supportsDineIn").isEmpty()).andExpect(jsonPath("$.data.supportsDelivery").value(true));
    }

    /** 新建食堂也要写正式类型；有窗口的食堂不可切回普通餐厅，冲突不能误报审核成功。 */
    @Test
    void shouldCreateCanteenAndProtectExistingWindowReferences() throws Exception {
        var submission = valid(); submission.put("clientRequestId", UUID.randomUUID().toString()); submission.put("restaurantType", "CANTEEN");
        String id = submitted("/api/restaurant-submissions", submission);
        var approved = valid(); approved.put("restaurantType", "CANTEEN"); approved.put("supportsDineIn", true);
        var response = perform(post("/api/admin/restaurant-requests/" + id + "/approve"), admin, approved).andExpect(status().isOk()).andReturn();
        String target = json.readTree(response.getResponse().getContentAsString()).at("/data/restaurantId").asString();
        assertThat(jdbc.queryForObject("SELECT restaurant_type FROM restaurants WHERE id=?", String.class, target)).isEqualTo("CANTEEN");
        submission.put("clientRequestId", UUID.randomUUID().toString()); submission.put("restaurantType", "REGULAR");
        String nextId = submitted("/api/restaurants/" + target + "/change-requests", submission);
        jdbc.update("INSERT INTO canteen_windows(canteen_id,name,floor_number,created_by) VALUES(?,'窗口夹具',1,33)", target);
        try {
            perform(post("/api/admin/restaurant-requests/" + nextId + "/approve"), admin, valid()).andExpect(status().isConflict());
            assertThat(jdbc.queryForObject("SELECT status FROM restaurant_change_requests WHERE id=?", String.class, nextId)).isEqualTo("PENDING");
        } finally { jdbc.update("DELETE FROM canteen_windows WHERE canteen_id=?", target); }
        perform(post("/api/admin/restaurant-requests/" + nextId + "/approve"), admin, valid()).andExpect(status().isOk());
        mvc.perform(get("/api/restaurants/" + target)).andExpect(status().isOk()).andExpect(jsonPath("$.data.restaurantType").value("REGULAR"));
    }

    /** 带图申请通过或驳回均保留原关联，用户原请求仍可重试，不发布或删除云对象。 */
    @Test
    void shouldKeepApplicationPhotoReferencesAcrossBothDecisions() throws Exception {
        for (int index = 0; index < 2; index++) {
            long file = 901L + index;
            jdbc.update("INSERT INTO media_files(id,uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,purpose,expires_at) VALUES(?,22,'test-bucket',?,'image/jpeg',4,1,1,UNHEX(SHA2(?,256)),'TEMPORARY','RESTAURANT',CURRENT_TIMESTAMP+INTERVAL 1 DAY)", file, "restaurant-images/" + file + ".jpg", "photo" + file);
            var input = valid(); input.put("clientRequestId", UUID.randomUUID().toString()); input.put("photoIds", List.of(Long.toString(file)));
            String id = submitted("/api/restaurant-submissions", input);
            String endpoint = "/api/admin/restaurant-requests/" + id;
            if (index == 0) perform(post(endpoint + "/approve"), admin, valid()).andExpect(status().isOk());
            else perform(post(endpoint + "/reject"), admin, Map.of("reason", "图片模糊")).andExpect(status().isOk());
            perform(post("/api/restaurant-submissions"), other, input).andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id));
            assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=?", String.class, file)).isEqualTo("BOUND");
            assertThat(jdbc.queryForObject("SELECT media_file_id FROM restaurant_change_photos WHERE request_id=? AND position=0", Long.class, id)).isEqualTo(file);
            verifyNoInteractions(storage);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isEqualTo(1);
    }

    /** 连历史创建者也不能直接维护正式资料；前端隐藏和JWT自报角色不构成授权。 */
    @Test
    void shouldRestrictAllDirectMaintenanceToAdministrators() throws Exception {
        String target = create(valid()).get("restaurant").get("id").asString();
        jdbc.update("UPDATE restaurants SET created_by=11 WHERE id=?", target);
        for (String user : List.of(owner, other)) {
            perform(post("/api/restaurants"), user, valid()).andExpect(status().isForbidden());
            perform(put("/api/restaurants/" + target), user, valid()).andExpect(status().isForbidden());
            perform(delete("/api/restaurants/" + target), user, null).andExpect(status().isForbidden());
            perform(get("/api/restaurants/" + target + "/management"), user, null).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isEqualTo(1);
    }

    /** 实际通过新店、管理员修订、生成目标ID及用户原请求重试形成完整闭环。 */
    @Test
    void shouldApproveNewSubmissionAndReplayBothReceipts() throws Exception {
        var original = valid(); original.put("clientRequestId", UUID.randomUUID().toString());
        String id = submitted("/api/restaurant-submissions", original);
        String hash = jdbc.queryForObject("SELECT HEX(request_hash) FROM restaurant_change_requests WHERE id=?", String.class, id);
        String proposed = jdbc.queryForObject("SELECT proposed_data FROM restaurant_change_requests WHERE id=?", String.class, id);
        var edited = valid(); edited.put("name", "  管理员修订  "); edited.put("tags", List.of(" 招牌 ", "招牌"));
        String endpoint = "/api/admin/restaurant-requests/" + id + "/approve";
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edited))).andExpect(status().isUnauthorized());
        perform(post(endpoint), other, edited).andExpect(status().isForbidden());
        var first = perform(post(endpoint), admin, edited).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.replayed").value(false)).andExpect(jsonPath("$.data.status").value("APPROVED")).andReturn();
        var receipt = json.readTree(first.getResponse().getContentAsString()).get("data");
        String target = receipt.get("restaurantId").asString();
        assertThat(jdbc.queryForObject("SELECT name FROM restaurants WHERE id=?", String.class, target)).isEqualTo("管理员修订");
        assertThat(jdbc.queryForObject("SELECT created_by FROM restaurants WHERE id=?", Long.class, target)).isEqualTo(33);
        assertThat(jdbc.queryForObject("SELECT before_data FROM restaurant_change_requests WHERE id=?", String.class, id)).isNull();
        // 分类在首次通过后被停用，重试仍应返回原有成功结果。
        jdbc.update("UPDATE restaurant_categories SET enabled=FALSE WHERE code='OTHER'");
        perform(post(endpoint), admin, edited).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.reviewedAt").value(receipt.get("reviewedAt").asString()));
        jdbc.update("UPDATE restaurant_categories SET enabled=TRUE WHERE code='OTHER'");
        perform(post("/api/restaurant-submissions"), other, original).andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT HEX(request_hash) FROM restaurant_change_requests WHERE id=?", String.class, id)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT proposed_data FROM restaurant_change_requests WHERE id=?", String.class, id)).isEqualTo(proposed);
        edited.put("name", "不同审核内容");
        perform(post(endpoint), admin, edited).andExpect(status().isConflict());
        perform(post("/api/admin/restaurant-requests/" + id + "/reject"), admin, Map.of("reason", "信息不实")).andExpect(status().isConflict());
    }

    /** 纠错快照记录事务内最新资料；不做页面过期检测，不覆盖归属或评分。 */
    @Test
    void shouldApproveCorrectionWithBeforeAndApprovedSnapshots() throws Exception {
        String target = create(valid()).get("restaurant").get("id").asString();
        jdbc.update("UPDATE restaurants SET created_by=11 WHERE id=?", target);
        var submission = valid(); submission.put("clientRequestId", UUID.randomUUID().toString());
        String id = submitted("/api/restaurants/" + target + "/change-requests", submission);
        jdbc.update("UPDATE restaurants SET name='审核前最新名称' WHERE id=?", target);
        var edited = valid(); edited.put("name", "通过后名称"); edited.put("tags", List.of("新标签"));
        perform(post("/api/admin/restaurant-requests/" + id + "/approve"), admin, edited).andExpect(status().isOk());
        perform(get("/api/admin/restaurant-requests/" + id), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.before.name").value("审核前最新名称"))
                .andExpect(jsonPath("$.data.before.tags[0]").value("原标签"))
                .andExpect(jsonPath("$.data.approved.name").value("通过后名称"));
        assertThat(jdbc.queryForObject("SELECT created_by FROM restaurants WHERE id=?", Long.class, target)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM restaurants WHERE id=?", Long.class, target)).isEqualTo(33);
        assertThat(jdbc.queryForList("SELECT tag FROM restaurant_tags WHERE restaurant_id=?", String.class, target)).containsExactly("新标签");
    }

    /** 删除目标不能通过审核复活，但仍允许驳回；原因规范化后可安全重试。 */
    @Test
    void shouldRejectDeletedTargetWithoutChangingBusinessData() throws Exception {
        String target = create(valid()).get("restaurant").get("id").asString();
        var submission = valid(); submission.put("clientRequestId", UUID.randomUUID().toString());
        String id = submitted("/api/restaurants/" + target + "/change-requests", submission);
        perform(delete("/api/restaurants/" + target), admin, null).andExpect(status().isOk());
        String endpoint = "/api/admin/restaurant-requests/" + id;
        perform(post(endpoint + "/approve"), admin, valid()).andExpect(status().isConflict());
        perform(post(endpoint + "/reject"), admin, Map.of("reason", "　\t ")).andExpect(status().isBadRequest());
        perform(post(endpoint + "/reject"), admin, Map.of("reason", "信息不实", "reviewedBy", 11)).andExpect(status().isBadRequest());
        perform(post(endpoint + "/reject"), other, Map.of("reason", "信息不实")).andExpect(status().isForbidden());
        perform(post(endpoint + "/reject"), admin, Map.of("reason", " 信息不实 ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rejectReason").value("信息不实"));
        perform(post(endpoint + "/reject"), admin, Map.of("reason", "信息不实")).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
        perform(post(endpoint + "/reject"), admin, Map.of("reason", "无效地址")).andExpect(status().isConflict());
        perform(post(endpoint + "/approve"), admin, valid()).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM restaurants WHERE id=?", Boolean.class, target)).isTrue();
        assertThat(jdbc.queryForObject("SELECT approved_data FROM restaurant_change_requests WHERE id=?", String.class, id)).isNull();
    }

    /** 最后一步审核持久化失败，已创建/已更新的商户和标签全部回滚。 */
    @Test
    void shouldRollbackPublicationWhenDecisionPersistenceFails() throws Exception {
        String target = create(valid()).get("restaurant").get("id").asString();
        var submission = valid(); submission.put("clientRequestId", UUID.randomUUID().toString());
        String update = submitted("/api/restaurants/" + target + "/change-requests", submission);
        submission.put("clientRequestId", UUID.randomUUID().toString());
        String create = submitted("/api/restaurant-submissions", submission);
        doThrow(new IllegalStateException("test-only decision failure")).when(reviewMapper).approve(any());
        var edited = valid(); edited.put("name", "不应生效"); edited.put("tags", List.of("新标签"));
        for (String id : List.of(update, create)) perform(post("/api/admin/restaurant-requests/" + id + "/approve"), admin, edited).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM restaurants WHERE id=?", String.class, target)).isEqualTo(valid().get("name"));
        assertThat(jdbc.queryForList("SELECT tag FROM restaurant_tags WHERE restaurant_id=?", String.class, target)).containsExactly("原标签");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_change_requests WHERE status='PENDING' AND reviewed_by IS NULL", Long.class)).isEqualTo(2);
    }

    /** 两名真实管理员同时通过，只创建一家店，另一请求返回重试回执；审核与驳回竞争只有一个终态。 */
    @Test
    void shouldSerializeConcurrentDecisions() throws Exception {
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT 11,id FROM roles WHERE code='ROLE_ADMIN'");
        var submission = valid(); submission.put("clientRequestId", UUID.randomUUID().toString());
        String id = submitted("/api/restaurant-submissions", submission);
        String endpoint = "/api/admin/restaurant-requests/" + id;
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return perform(post(endpoint + "/approve"), admin, valid()).andReturn(); });
            var b = executor.submit(() -> { start.await(); return perform(post(endpoint + "/approve"), owner, valid()).andReturn(); });
            start.countDown();
            var first = a.get(15, TimeUnit.SECONDS); var second = b.get(15, TimeUnit.SECONDS);
            assertThat(first.getResponse().getStatus()).isEqualTo(200); assertThat(second.getResponse().getStatus()).isEqualTo(200);
            assertThat(List.of(json.readTree(first.getResponse().getContentAsString()).at("/data/replayed").asBoolean(),
                    json.readTree(second.getResponse().getContentAsString()).at("/data/replayed").asBoolean())).containsExactlyInAnyOrder(false, true);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isEqualTo(1);
        submission.put("clientRequestId", UUID.randomUUID().toString());
        String race = submitted("/api/restaurant-submissions", submission);
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { gate.await(); return perform(post("/api/admin/restaurant-requests/" + race + "/approve"), admin, valid()).andReturn(); });
            var b = executor.submit(() -> { gate.await(); return perform(post("/api/admin/restaurant-requests/" + race + "/reject"), owner, Map.of("reason", "信息不实")).andReturn(); });
            gate.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS).getResponse().getStatus(), b.get(15, TimeUnit.SECONDS).getResponse().getStatus())).containsExactlyInAnyOrder(200, 409);
        }
    }

    /** 真实上传→用户投稿→管理员通过→匿名图片读取，包含JPEG处理、事务及安全链，只有OSS/Redis替身。 */
    @Test
    void shouldPublishUploadedPhotosThroughCompleteHttpFlow() throws Exception {
        var source = new java.awt.image.BufferedImage(24, 18, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(source, "jpeg", bytes);
        var upload = mvc.perform(multipart("/api/files/images").file(new org.springframework.mock.web.MockMultipartFile(
                "file", "shop.jpg", "image/jpeg", bytes.toByteArray())).param("purpose", "RESTAURANT")
                .header("Authorization", "Bearer " + other)).andExpect(status().isCreated()).andReturn();
        String file = json.readTree(upload.getResponse().getContentAsString()).at("/data/fileId").asString();
        var stored = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(anyString(), anyString(), stored.capture());
        var input = valid(); input.put("clientRequestId", UUID.randomUUID().toString()); input.put("photoIds", List.of(file));
        String id = submitted("/api/restaurant-submissions", input);
        String originalHash = jdbc.queryForObject("SELECT HEX(request_hash) FROM restaurant_change_requests WHERE id=?", String.class, id);
        var receipt = approveAlbum(id, valid());
        String target = receipt.get("restaurantId").asString();
        assertThat(receipt.at("/publishedPhotos/0/cover").asBoolean()).isTrue();
        assertThat(receipt.at("/publishedPhotos/0/position").asInt()).isZero();
        var list = publicAlbum(target, 20, null);
        String url = list.at("/records/0/url").asString();
        assertThat(list.get("records").size()).isEqualTo(1); assertThat(list.get("nextCursor").isNull()).isTrue();
        assertThat(list.at("/records/0").has("mediaFileId")).isFalse();
        assertThat(list.at("/records/0").has("sourceRequestId")).isFalse();
        verify(storage, never()).read(anyString(), anyString());
        when(storage.read(anyString(), anyString())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return stored.getValue();
        });
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(stored.getValue()));
        perform(get("/api/admin/restaurant-requests/" + id), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photos.length()").value(1)).andExpect(jsonPath("$.data.publishedPhotos[0].url").value(url));
        perform(post("/api/restaurant-submissions"), other, input).andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id));
        perform(delete("/api/files/images/" + file), other, null).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT HEX(request_hash) FROM restaurant_change_requests WHERE id=?", String.class, id)).isEqualTo(originalHash);
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=?", String.class, file)).isEqualTo("BOUND");
        verify(storage, times(1)).put(anyString(), anyString(), any());
        verify(storage, never()).delete(anyString(), anyString());
    }

    /** 纠错图片只追加，空图不清空，相同审核重试不重复发布，游标分页不丢失顺序或封面。 */
    @Test
    void shouldAppendAlbumAndPageUsingStablePhotoCursor() throws Exception {
        String target = create(valid()).get("restaurant").get("id").asString();
        long first = albumFile(), second = albumFile(), third = albumFile();
        String a = albumRequest(target, first, second);
        var receipt = approveAlbum(a, valid());
        assertThat(receipt.get("publishedPhotos").size()).isEqualTo(2);
        assertThat(approveAlbum(a, valid()).get("replayed").asBoolean()).isTrue();
        String b = albumRequest(target, third); approveAlbum(b, valid());
        String empty = albumRequest(target); approveAlbum(empty, valid());
        assertThat(jdbc.queryForList("SELECT media_file_id FROM restaurant_photos WHERE restaurant_id=? ORDER BY position", Long.class, target))
                .containsExactly(first, second, third);
        var page = publicAlbum(target, 1, null);
        String firstId = page.at("/records/0/id").asString(), cursor = page.get("nextCursor").asString();
        assertThat(cursor).isEqualTo(firstId); assertThat(page.at("/records/0/cover").asBoolean()).isTrue();
        var next = publicAlbum(target, 1, cursor);
        assertThat(next.at("/records/0/position").asInt()).isEqualTo(1); assertThat(next.at("/records/0/cover").asBoolean()).isFalse();
        var last = publicAlbum(target, 1, next.get("nextCursor").asString());
        assertThat(last.at("/records/0/position").asInt()).isEqualTo(2); assertThat(last.get("nextCursor").isNull()).isTrue();
        assertThat(publicAlbum(target, 1, last.at("/records/0/id").asString()).get("records").isEmpty()).isTrue();
        String otherTarget = create(valid()).get("restaurant").get("id").asString();
        assertThat(publicAlbum(otherTarget, 20, null).get("records").isEmpty()).isTrue();
        mvc.perform(get("/api/restaurants/" + otherTarget + "/photos").param("cursor", firstId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/restaurants/" + otherTarget + "/photos/" + firstId)).andExpect(status().isNotFound());
        for (String bad : List.of("0", "51", "2147483647", "x"))
            mvc.perform(get("/api/restaurants/" + target + "/photos").param("limit", bad)).andExpect(status().isBadRequest());
        for (String bad : List.of("0", "01", "-1", "9223372036854775808"))
            mvc.perform(get("/api/restaurants/" + target + "/photos").param("cursor", bad)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/restaurants/999999/photos")).andExpect(status().isNotFound());
        mvc.perform(post("/api/restaurants/" + target + "/photos")).andExpect(status().isUnauthorized());
        perform(post("/api/restaurants/" + target + "/photos"), admin, null).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(storage);
    }

    /** 两份不同申请同时给同一商户追加，主行锁保证每份图片连续且只存在一个默认封面。 */
    @Test
    void shouldSerializeConcurrentAlbumAppendsForSameRestaurant() throws Exception {
        String target = create(valid()).get("restaurant").get("id").asString();
        long a1 = albumFile(), a2 = albumFile(), b1 = albumFile(), b2 = albumFile();
        String a = albumRequest(target, a1, a2), b = albumRequest(target, b1, b2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return approveAlbum(a, valid()); });
            var second = executor.submit(() -> { start.await(); return approveAlbum(b, valid()); });
            start.countDown(); first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        var positions = jdbc.queryForList("SELECT position FROM restaurant_photos WHERE restaurant_id=? ORDER BY position", Integer.class, target);
        assertThat(positions).containsExactly(0, 1, 2, 3);
        var aRows = jdbc.queryForList("SELECT media_file_id FROM restaurant_photos WHERE source_request_id=? ORDER BY position", Long.class, a);
        var bRows = jdbc.queryForList("SELECT media_file_id FROM restaurant_photos WHERE source_request_id=? ORDER BY position", Long.class, b);
        assertThat(aRows).containsExactly(a1, a2); assertThat(bRows).containsExactly(b1, b2);
        for (String request : List.of(a, b))
            assertThat(jdbc.queryForObject("SELECT MAX(position)-MIN(position) FROM restaurant_photos WHERE source_request_id=?", Integer.class, request)).isEqualTo(1);
        assertThat(publicAlbum(target, 20, null).get("records").size()).isEqualTo(4);
        verifyNoInteractions(storage);
    }

    /** 同一带图新店申请被同时通过，只创建一个商户、一套相册及一份首次回执。 */
    @Test
    void shouldDeduplicateConcurrentApprovalOfPhotoSubmission() throws Exception {
        String id = albumRequest(null, albumFile(), albumFile());
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { gate.await(); return approveAlbum(id, valid()); });
            var b = executor.submit(() -> { gate.await(); return approveAlbum(id, valid()); });
            gate.countDown(); var first = a.get(15, TimeUnit.SECONDS); var second = b.get(15, TimeUnit.SECONDS);
            assertThat(first.get("restaurantId").asString()).isEqualTo(second.get("restaurantId").asString());
            assertThat(first.get("publishedPhotos")).isEqualTo(second.get("publishedPhotos"));
            assertThat(List.of(first.get("replayed").asBoolean(), second.get("replayed").asBoolean())).containsExactlyInAnyOrder(false, true);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_photos", Long.class)).isEqualTo(2);
    }

    /** 关联第二张失败或最后审核更新失败，主表、标签、全部相册关联与终态都回滚。 */
    @Test
    void shouldRollbackAlbumAndBusinessForInsertAndDecisionFailures() throws Exception {
        String target = create(valid()).get("restaurant").get("id").asString();
        String update = albumRequest(target, albumFile(), albumFile());
        String fresh = albumRequest(null, albumFile(), albumFile());
        doThrow(new IllegalStateException("test-only second photo failure")).when(photoMapper)
                .insert(argThat(row -> row.getPosition() == 1));
        var edited = valid(); edited.put("name", "不可部分发布"); edited.put("restaurantType", "CANTEEN"); edited.put("tags", List.of("新标签"));
        for (String id : List.of(update, fresh))
            perform(post("/api/admin/restaurant-requests/" + id + "/approve"), admin, edited).andExpect(status().isInternalServerError());
        assertAlbumRollback(target);
        reset(photoMapper);
        doThrow(new IllegalStateException("test-only final decision failure")).when(reviewMapper).approve(any());
        for (String id : List.of(update, fresh))
            perform(post("/api/admin/restaurant-requests/" + id + "/approve"), admin, edited).andExpect(status().isInternalServerError());
        assertAlbumRollback(target);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE status='BOUND'", Long.class)).isEqualTo(4);
        verifyNoInteractions(storage);
    }

    /** 文件绑定后仍须在审核时校验归属、用途和状态；无效文件不能只通过文字并漏发图片。 */
    @ParameterizedTest
    @ValueSource(strings = {"PURPOSE", "OWNER", "TEMPORARY", "MISSING"})
    void shouldRefuseInvalidPhotoAtPublication(String corruption) throws Exception {
        String target = create(valid()).get("restaurant").get("id").asString();
        long file = albumFile(); String id = albumRequest(target, file);
        switch (corruption) {
            case "PURPOSE" -> jdbc.update("UPDATE media_files SET purpose='REVIEW' WHERE id=?", file);
            case "OWNER" -> jdbc.update("UPDATE media_files SET uploader_user_id=11 WHERE id=?", file);
            case "TEMPORARY" -> jdbc.update("UPDATE media_files SET status='TEMPORARY',bound_at=NULL,expires_at=CURRENT_TIMESTAMP+INTERVAL 1 DAY WHERE id=?", file);
            case "MISSING" -> jdbc.update("DELETE FROM media_files WHERE id=?", file);
            default -> throw new IllegalArgumentException(corruption);
        }
        perform(post("/api/admin/restaurant-requests/" + id + "/approve"), admin, valid()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_NOT_BINDABLE"));
        assertThat(jdbc.queryForObject("SELECT status FROM restaurant_change_requests WHERE id=?", String.class, id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_photos", Long.class)).isZero(); verifyNoInteractions(storage);
    }

    /** 待审、驳回、迁移前旧通过照片均不自动公开；旧通过重试只回放，不隐式补发历史图片。 */
    @Test
    void shouldKeepPendingRejectedAndLegacyPhotosPrivate() throws Exception {
        String target = create(valid()).get("restaurant").get("id").asString();
        long pendingFile = albumFile(); String pending = albumRequest(target, pendingFile);
        assertThat(publicAlbum(target, 20, null).get("records").isEmpty()).isTrue();
        mvc.perform(get("/api/restaurants/" + target + "/photos/" + pendingFile)).andExpect(status().isNotFound());
        perform(get("/api/admin/restaurant-requests/" + pending + "/photos/0"), other, null).andExpect(status().isForbidden());
        perform(post("/api/admin/restaurant-requests/" + pending + "/reject"), admin, Map.of("reason", "图片模糊"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.publishedPhotos.length()").value(0));
        long oldFile = albumFile(); String legacy = albumRequest(target, oldFile);
        jdbc.update("UPDATE restaurant_change_requests SET status='APPROVED',reviewed_by=33,reviewed_at=CURRENT_TIMESTAMP(6),approved_data=?,before_data=JSON_OBJECT() WHERE id=?", json.writeValueAsString(valid()), legacy);
        assertThat(approveAlbum(legacy, valid()).get("publishedPhotos").isEmpty()).isTrue();
        assertThat(publicAlbum(target, 20, null).get("records").isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_change_photos", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE status='BOUND'", Long.class)).isEqualTo(2);
        verifyNoInteractions(storage);
    }

    /** 公开读取逐次检查商户/关联/文件；软删除撤销访问，恢复可读，云故障503且不破坏相册。 */
    @Test
    void shouldEnforcePublicPhotoVisibilityAndIndependentStorageFailures() throws Exception {
        String target = create(valid()).get("restaurant").get("id").asString();
        long file = albumFile(); String id = albumRequest(target, file);
        String url = approveAlbum(id, valid()).at("/publishedPhotos/0/url").asString();
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        when(storage.read(anyString(), anyString())).thenReturn(jpeg);
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().bytes(jpeg));
        clearInvocations(storage);
        perform(delete("/api/restaurants/" + target), admin, null).andExpect(status().isOk());
        mvc.perform(get(url)).andExpect(status().isNotFound());
        mvc.perform(get("/api/restaurants/" + target + "/photos")).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
        perform(get("/api/admin/restaurant-requests/" + id), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetDeleted").value(true)).andExpect(jsonPath("$.data.publishedPhotos.length()").value(1));
        perform(get("/api/admin/restaurant-requests/" + id + "/photos/0"), admin, null).andExpect(status().isOk());
        perform(post("/api/restaurants/" + target + "/restore"), admin, null).andExpect(status().isOk());
        mvc.perform(get(url)).andExpect(status().isOk());
        when(storage.read(anyString(), anyString())).thenThrow(new com.ginkgo.foodmap.common.exception.BusinessException(com.ginkgo.foodmap.file.exception.FileErrorCode.UNAVAILABLE));
        mvc.perform(get(url)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("FILE_STORAGE_UNAVAILABLE"));
        assertThat(publicAlbum(target, 20, null).get("records").size()).isEqualTo(1);
        clearInvocations(storage);
        jdbc.update("UPDATE media_files SET purpose='REVIEW' WHERE id=?", file);
        mvc.perform(get(url)).andExpect(status().isNotFound()); verifyNoInteractions(storage);
        jdbc.update("UPDATE media_files SET purpose='RESTAURANT',uploader_user_id=11 WHERE id=?", file);
        mvc.perform(get(url)).andExpect(status().isNotFound()); verifyNoInteractions(storage);
        jdbc.update("UPDATE media_files SET uploader_user_id=22,status='PENDING_DELETE',expires_at=CURRENT_TIMESTAMP(6) WHERE id=?", file);
        mvc.perform(get(url)).andExpect(status().isNotFound()); verifyNoInteractions(storage);
    }

    /** 食堂和普通餐厅共用正式相册协议，不依赖餐厅类型或窗口查询是否接入。 */
    @Test
    void shouldExposeCanteenAlbumUsingSamePublicContract() throws Exception {
        String id = albumRequest(null, albumFile()); var edited = valid(); edited.put("restaurantType", "CANTEEN");
        var receipt = approveAlbum(id, edited); String target = receipt.get("restaurantId").asString();
        mvc.perform(get("/api/restaurants/" + target)).andExpect(status().isOk()).andExpect(jsonPath("$.data.restaurantType").value("CANTEEN"));
        assertThat(publicAlbum(target, 20, null).get("records").size()).isEqualTo(1);
    }

    /** 列表只批量查询当前页封面；默认首图来自正式关联，待审/无图不补点评图片，空页不查封面。 */
    @Test
    void shouldReturnBatchCoverMetadataWithoutLoadingAlbumPerRestaurant() throws Exception {
        String with = create(valid()).get("restaurant").get("id").asString();
        String without = create(valid()).get("restaurant").get("id").asString();
        String pending = albumRequest(without, albumFile());
        String request = albumRequest(with, albumFile(), albumFile());
        var receipt = approveAlbum(request, valid());
        String coverId = receipt.at("/publishedPhotos/0/id").asString();
        clearInvocations(photoMapper, storage);
        var response = mvc.perform(get("/api/restaurants").param("pageSize", "100")).andExpect(status().isOk()).andReturn();
        var rows = json.readTree(response.getResponse().getContentAsString()).at("/data/records");
        for (var row : rows) {
            if (row.get("id").asString().equals(with)) {
                assertThat(row.at("/coverPhoto/id").asString()).isEqualTo(coverId);
                assertThat(row.at("/coverPhoto/url").asString()).isEqualTo("/api/restaurants/" + with + "/photos/" + coverId);
            } else assertThat(row.get("coverPhoto").isNull()).isTrue();
        }
        verify(photoMapper, times(1)).covers(argThat(ids -> ids.size() == 2 && ids.contains(Long.parseLong(with)) && ids.contains(Long.parseLong(without))));
        verifyNoInteractions(storage);
        mvc.perform(get("/api/restaurants/" + with)).andExpect(status().isOk()).andExpect(jsonPath("$.data.coverPhoto.id").value(coverId));
        perform(get("/api/restaurants/" + with + "/management"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurant.coverPhoto.id").value(coverId));
        clearInvocations(photoMapper);
        mvc.perform(get("/api/restaurants").param("keyword", "不会命中的封面查询")).andExpect(status().isOk()).andExpect(jsonPath("$.data.records.length()").value(0));
        verify(photoMapper, never()).covers(anyList());
        assertThat(jdbc.queryForObject("SELECT status FROM restaurant_change_requests WHERE id=?", String.class, pending)).isEqualTo("PENDING");
    }

    /** 为边界测试准备临时文件，仍通过真实投稿事务绑定；仅完整上传用例替身OSS保存真实JPEG。 */
    private long albumFile() {
        long id = ++albumFileSequence;
        jdbc.update("INSERT INTO media_files(id,uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,purpose,expires_at) VALUES(?,22,'album-test',?,'image/jpeg',4,1,1,UNHEX(SHA2(?,256)),'TEMPORARY','RESTAURANT',CURRENT_TIMESTAMP+INTERVAL 1 DAY)", id, "restaurant-images/album-" + id + ".jpg", "album-" + id);
        return id;
    }

    private String albumRequest(String target, long... files) throws Exception {
        var input = valid(); input.put("clientRequestId", UUID.randomUUID().toString());
        input.put("photoIds", Arrays.stream(files).mapToObj(Long::toString).toList());
        return submitted(target == null ? "/api/restaurant-submissions" : "/api/restaurants/" + target + "/change-requests", input);
    }

    private JsonNode approveAlbum(String id, Map<String, Object> input) throws Exception {
        var result = perform(post("/api/admin/restaurant-requests/" + id + "/approve"), admin, input).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private JsonNode publicAlbum(String target, int limit, String cursor) throws Exception {
        var request = get("/api/restaurants/" + target + "/photos").param("limit", Integer.toString(limit));
        if (cursor != null) request.param("cursor", cursor);
        var result = mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private void assertAlbumRollback(String target) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_photos", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM restaurants WHERE id=?", String.class, target)).isEqualTo(valid().get("name"));
        assertThat(jdbc.queryForObject("SELECT restaurant_type FROM restaurants WHERE id=?", String.class, target)).isEqualTo("REGULAR");
        assertThat(jdbc.queryForList("SELECT tag FROM restaurant_tags WHERE restaurant_id=? ORDER BY position", String.class, target)).containsExactly("原标签");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_change_requests WHERE status='PENDING' AND reviewed_by IS NULL", Long.class)).isEqualTo(2);
    }

    /** 测试只通过真实用户提交接口创建申请，不在业务库中制造审核样例。 */
    private String submitted(String endpoint, Map<String, Object> input) throws Exception {
        var response = perform(post(endpoint), other, input).andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).at("/data/id").asString();
    }

    private Map<String, Object> valid() {
        var request = new LinkedHashMap<String, Object>();
        request.put("name", "CRUD测试夹具"); request.put("categoryCode", "OTHER");
        request.put("priceKind", "UNKNOWN"); request.put("latitude", 30.7544); request.put("longitude", 103.9312);
        request.put("locationConfirmed", true); request.put("tags", List.of("原标签"));
        return request;
    }

    private JsonNode create(Map<String, Object> request) throws Exception {
        var result = perform(post("/api/restaurants"), admin, request).andExpect(status().isCreated())
                .andExpect(header().exists("Location")).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder request, String token,
                                                                       Map<String, ?> body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request);
    }

    private List<String> tags(JsonNode restaurant) {
        var result = new ArrayList<String>();
        restaurant.get("tags").forEach(tag -> result.add(tag.asString()));
        return result;
    }

    private String token(long user, long version, boolean forgedAdministrator) {
        return token(user, version, forgedAdministrator, false);
    }

    private String token(long user, long version, boolean forgedAdministrator, boolean expired) {
        var now = Instant.now();
        var properties = authentication.getJwt();
        var claims = JwtClaimsSet.builder().issuer(properties.getIssuer()).audience(List.of(properties.getAudience()))
                .subject(Long.toString(user)).issuedAt(now.minusSeconds(120)).expiresAt(now.plusSeconds(expired ? -60 : 600))
                .id(UUID.randomUUID().toString()).claim("credential_version", version)
                .claim("roles", forgedAdministrator ? List.of("ROLE_ADMIN") : List.of()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    private static String username() {
        return System.getenv().getOrDefault("RESTAURANT_TEST_DB_USERNAME", "root");
    }

    private static String password() {
        String password = System.getenv("RESTAURANT_TEST_DB_PASSWORD");
        if (password == null) throw new IllegalStateException("Set RESTAURANT_TEST_DB_PASSWORD for isolated tests");
        return password;
    }

    private static String url(String name) {
        String server = System.getenv().getOrDefault("RESTAURANT_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Restaurant write tests require an explicit loopback MySQL server");
        }
        return server + name + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"
                + "&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), username(), password());
    }
}
