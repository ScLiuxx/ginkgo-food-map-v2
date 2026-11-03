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
    @MockitoSpyBean private RestaurantWriteMapper mapper;
    private String owner;
    private String other;
    private String admin;

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
        reset(mapper, sessions);
        jdbc.update("DELETE FROM restaurant_change_requests");
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
        assertThat(jdbc.queryForObject("SELECT created_by FROM restaurants WHERE id=?", Long.class, id)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM restaurants WHERE id=?", Long.class, id)).isEqualTo(11);
        assertThat(created.get("restaurant").has("sourceType")).isFalse();
        assertThat(created.get("restaurant").has("coordinateSystem")).isFalse();
        mvc.perform(get("/api/restaurants/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").isEmpty()).andExpect(jsonPath("$.data.reviewCount").value(0));
        perform(get("/api/restaurants/" + id + "/management"), owner, null)
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist());

        input.put("name", "修改后");
        input.put("businessStatus", "CLOSED"); input.put("tags", List.of("新标签", "第二项"));
        perform(put("/api/restaurants/" + id), owner, input).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist());
        mvc.perform(get("/api/restaurants/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.tags[0]").value("新标签"));
        perform(delete("/api/restaurants/" + id), owner, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rowVersion").doesNotExist())
                .andExpect(jsonPath("$.data.deleted").value(true));
        mvc.perform(get("/api/restaurants/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/restaurants")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_tags WHERE restaurant_id=?", Long.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT delete_reason_code FROM restaurants WHERE id=?", String.class, id)).isEqualTo("OWNER_REQUEST");
        assertThat(jdbc.queryForObject("SELECT deleted_by FROM restaurants WHERE id=?", Long.class, id)).isEqualTo(11);
        perform(get("/api/restaurants/" + id + "/management"), owner, null).andExpect(status().isNotFound());
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
        assertThat(jdbc.queryForObject("SELECT created_by FROM restaurants WHERE id=?", Long.class, id)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM restaurants WHERE id=?", Long.class, id)).isEqualTo(33);
    }

    /** 普通账号即使JWT伪报角色也不能读取私有资料、改删他人餐厅或恢复删除记录。 */
    @Test
    void shouldEnforceOwnerAndDatabaseAdministratorRole() throws Exception {
        String id = create(valid()).get("restaurant").get("id").asString();
        var update = valid();
        perform(get("/api/restaurants/" + id + "/management"), other, null).andExpect(status().isForbidden());
        perform(put("/api/restaurants/" + id), other, update).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RESTAURANT_FORBIDDEN"));
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
        perform(put("/api/restaurants/" + id), owner, stalePage).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurant.pricePerPerson").value(20))
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist());
        perform(put("/api/restaurants/" + id), owner, stalePage).andExpect(status().isOk());
        perform(delete("/api/restaurants/" + id), owner, null).andExpect(status().isOk());
        perform(delete("/api/restaurants/" + id), owner, null).andExpect(status().isNotFound());
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
        perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_MALFORMED_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isZero();
    }

    /** 字段级空值/编码问题在DTO校验拒绝，金额/坐标精度与组合规则在服务拒绝。 */
    @ParameterizedTest
    @CsvSource({"name,''", "categoryCode,other", "categoryCode,OTHER ", "priceKind,unknown",
            "businessStatus,OPEN"})
    void shouldRejectInvalidCodesAndRequiredText(String field, String value) throws Exception {
        var input = valid(); input.put(field, value.equals("OTHER") ? "OTHER " : value);
        perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest());
    }

    /** 拒绝超范围/非整数金额、价格类型矛盾、未确认位置及不成对坐标。 */
    @Test
    void shouldValidatePriceAndLocationCombinations() throws Exception {
        for (Object price : List.of(0, 5001, -1, 15.5)) {
            var input = valid(); input.put("priceKind", "EXACT"); input.put("pricePerPerson", price);
            perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest());
        }
        var input = valid(); input.put("pricePerPerson", 20);
        perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest());
        input = valid(); input.put("priceKind", "ESTIMATE");
        perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest());
        for (String field : List.of("latitude", "longitude", "locationConfirmed", "name", "categoryCode", "tags")) {
            input = valid(); input.put(field, null);
            perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest());
        }
        for (double latitude : List.of(2.99, 54.01, 30.1234567)) {
            input = valid(); input.put("latitude", latitude);
            perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest());
        }
        for (double longitude : List.of(72.99, 136.01, 103.1234567)) {
            input = valid(); input.put("longitude", longitude);
            perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest());
        }
        input = valid(); input.put("locationConfirmed", false);
        perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurants", Long.class)).isZero();
    }

    /** 名称/地址按Unicode码点计数，emoji边界与中文一致；空白地址存NULL。 */
    @Test
    void shouldRespectUnicodeAndPriceBoundaries() throws Exception {
        var input = valid(); input.put("name", "🍜".repeat(40)); input.put("address", "🍜".repeat(80));
        input.put("priceKind", "EXACT"); input.put("pricePerPerson", 1); input.put("latitude", 3); input.put("longitude", 73);
        create(input);
        input.put("name", "🍜".repeat(41));
        perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest());
        input.put("name", "边界夹具"); input.put("address", "🍜".repeat(81));
        perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest());
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
        perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESTAURANT_INVALID_INPUT"));
        input.put("tags", List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"));
        perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest());
        input.put("tags", List.of(" "));
        perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest());
        input.put("tags", Arrays.asList("正常", null));
        perform(post("/api/restaurants"), owner, input).andExpect(status().isBadRequest());
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
        perform(post("/api/restaurants"), owner, valid()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESTAURANT_CATEGORY_UNAVAILABLE"));
        var input = valid();
        perform(put("/api/restaurants/" + id), owner, input).andExpect(status().isOk());
 input.put("categoryCode", "SICHUAN");
        perform(put("/api/restaurants/" + id), owner, input).andExpect(status().isOk());
 input.put("categoryCode", "OTHER");
        perform(put("/api/restaurants/" + id), owner, input).andExpect(status().isBadRequest());
        var absent = valid(); absent.put("categoryCode", "NON_EXISTENT");
        perform(post("/api/restaurants"), owner, absent).andExpect(status().isBadRequest());
    }

    /** 标签保存失败后主表新内容与旧标签全部回滚；创建也不会留下无标签的半成品。 */
    @Test
    void shouldRollbackWholeWriteWhenTagPersistenceFails() throws Exception {
        String id = create(valid()).get("restaurant").get("id").asString();
        doThrow(new IllegalStateException("test-only injected tag failure")).when(mapper).insertTags(anyLong(), anyList());
        var update = valid(); update.put("name", "不应保存"); update.put("tags", List.of("新"));
        perform(put("/api/restaurants/" + id), owner, update).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT name FROM restaurants WHERE id=?", String.class, id)).isEqualTo("CRUD测试夹具");
        assertThat(jdbc.queryForList("SELECT tag FROM restaurant_tags WHERE restaurant_id=? ORDER BY position", String.class, id))
                .containsExactly("原标签");
        perform(post("/api/restaurants"), owner, valid()).andExpect(status().isInternalServerError());
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
            var a = executor.submit(() -> { start.await(); return perform(put("/api/restaurants/" + id), owner, first).andReturn(); });
            var b = executor.submit(() -> { start.await(); return perform(put("/api/restaurants/" + id), owner, second).andReturn(); });
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
            var a = executor.submit(() -> { start.await(); return perform(put("/api/restaurants/" + id), owner, update).andReturn(); });
            var b = executor.submit(() -> { start.await(); return perform(delete("/api/restaurants/" + id), owner, null).andReturn(); });
            start.countDown();
            int editing = a.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            int deleting = b.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            assertThat(deleting).isEqualTo(200);
            assertThat(editing).isIn(200, 404);
        }
        mvc.perform(get("/api/restaurants/" + id)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_tags WHERE restaurant_id=?", Long.class, id)).isEqualTo(1);
    }

    /** 编辑/删除/恢复均无需版本，资源ID仍校验，已删除记录不能通过普通编辑复活。 */
    @Test
    void shouldAcceptVersionlessRequestsAndValidateResourceIdentity() throws Exception {
        String id = create(valid()).get("restaurant").get("id").asString();
        perform(put("/api/restaurants/" + id), owner, valid()).andExpect(status().isOk());
        perform(get("/api/restaurants/01/management"), owner, null).andExpect(status().isBadRequest());
        perform(get("/api/restaurants/999999/management"), admin, null).andExpect(status().isNotFound());
        perform(delete("/api/restaurants/" + id), owner, null).andExpect(status().isOk());
        perform(put("/api/restaurants/" + id), owner, valid()).andExpect(status().isNotFound());
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
        perform(put("/api/restaurants/" + id), owner, update).andExpect(status().isBadRequest());
        update.remove("sourceType"); update.put("tags", List.of("甲".repeat(40), "乙".repeat(41)));
        perform(put("/api/restaurants/" + id), owner, update).andExpect(status().isBadRequest());
        update.put("tags", List.of());
        perform(put("/api/restaurants/" + id), owner, update).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurant.tags").isEmpty())
                .andExpect(jsonPath("$.data.restaurant.pricePerPerson").isEmpty())
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant_tags WHERE restaurant_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT created_by FROM restaurants WHERE id=?", Long.class, id)).isEqualTo(11);
    }

    /** 管理响应和公开响应均不返回版本；旧版客户端提交rowVersion按未知JSON字段拒绝。 */
    @Test
    void shouldRemoveVersionFromManagementContract() throws Exception {
        String id = create(valid()).get("restaurant").get("id").asString();
        perform(get("/api/restaurants/" + id + "/management"), owner, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist())
                .andExpect(jsonPath("$.data.restaurant.createdBy").doesNotExist());
        mvc.perform(get("/api/restaurants/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVersion").doesNotExist())
                .andExpect(jsonPath("$.data.createdBy").doesNotExist());
        var obsolete = valid(); obsolete.put("rowVersion", "0");
        perform(put("/api/restaurants/" + id), owner, obsolete).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_MALFORMED_REQUEST"));
        perform(delete("/api/restaurants/" + id), owner, null).andExpect(status().isOk());
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

    private Map<String, Object> valid() {
        var request = new LinkedHashMap<String, Object>();
        request.put("name", "CRUD测试夹具"); request.put("categoryCode", "OTHER");
        request.put("priceKind", "UNKNOWN"); request.put("latitude", 30.7544); request.put("longitude", 103.9312);
        request.put("locationConfirmed", true); request.put("tags", List.of("原标签"));
        return request;
    }

    private JsonNode create(Map<String, Object> request) throws Exception {
        var result = perform(post("/api/restaurants"), owner, request).andExpect(status().isCreated())
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
