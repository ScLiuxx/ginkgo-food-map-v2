package com.foodmap.statistics;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.foodmap.notification.mapper.NotificationMapper;
import com.foodmap.security.TestJwtKeys;
import com.foodmap.security.config.AuthenticationProperties;
import com.foodmap.security.service.LoginSessionService;
import com.foodmap.statistics.config.ActivityStatisticsProperties;
import com.foodmap.statistics.mapper.ActivityStatisticsMapper;
import com.foodmap.statistics.policy.ActivityTime;
import com.foodmap.statistics.service.ActivityStatisticsService;
import com.foodmap.statistics.service.ActivityStatisticsWriter;
import com.foodmap.statistics.storage.ActivityRedisStore;
import com.foodmap.user.service.VerificationCodeService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;

/** 显式随机MySQL库、独立Redis端口；真实业务事务、SQL、鉴权、Lua和接口协作，不访问OSS。 */
@EnabledIfSystemProperty(named = "activity.backend.integration", matches = "true")
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.sql.init.mode=never",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.datasource.hikari.minimum-idle=0",
            "spring.datasource.hikari.maximum-pool-size=6"
        })
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
class ActivityStatisticsBackendIntegrationTest {
    private static String database;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @Autowired ActivityStatisticsService statistics;
    @Autowired ActivityStatisticsProperties properties;
    @Autowired ActivityStatisticsMapper mapper;
    @Autowired ActivityStatisticsWriter writer;
    @MockitoSpyBean ActivityRedisStore redis;
    @MockitoBean LoginSessionService sessions;
    @MockitoBean com.foodmap.security.service.LoginService login;
    @MockitoBean VerificationCodeService codes;
    @MockitoBean ActivityTime time;
    @MockitoSpyBean NotificationMapper notices;
    @MockitoSpyBean ActivityStatisticsMapper summaries;
    private Instant now;
    private final Set<LocalDate> redisDates = new HashSet<>();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        String port = required("ACTIVITY_TEST_REDIS_PORT");
        if (Integer.parseInt(port) == 6379) {
            throw new IllegalArgumentException("Isolated Redis required");
        }
        database = "foodmap_activity_backend_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> required("ACTIVITY_TEST_DB_PASSWORD"));
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> port);
        registry.add("spring.data.redis.database", () -> 0);
    }

    @BeforeEach
    void setup() throws Exception {
        reset(sessions, login, codes, time, notices, summaries, redis);
        // 只在本测试明确创建的随机库中清理表，绝不使用开发datasource配置。
        try (var c = connect(database);
                var s = c.createStatement()) {
            var tables =
                    jdbc.queryForList(
                            "SELECT table_name FROM information_schema.tables WHERE"
                                    + " table_schema=DATABASE()",
                            String.class);
            s.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String table : tables) {
                if (!table.equals("roles")
                        && !table.equals("flyway_schema_history")
                        && !table.equals("app_metadata")
                        && !table.equals("restaurant_categories")) {
                    if (!table.matches("[a-z_]+")) {
                        throw new IllegalStateException();
                    }
                    s.executeUpdate("TRUNCATE TABLE `" + table + "`");
                }
            }
            s.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        jdbc.update("DELETE FROM app_metadata WHERE metadata_key LIKE 'activity_stats_%'");
        clearRedis();
        ReflectionTestUtils.setField(statistics, "initialized", false);
        ReflectionTestUtils.setField(statistics, "started", null);
        ReflectionTestUtils.setField(statistics, "lastObserved", null);
        ((Set<?>) Objects.requireNonNull(ReflectionTestUtils.getField(statistics, "failedDates")))
                .clear();
        now = Instant.now().minusSeconds(30);
        when(time.now()).thenAnswer(call -> now);
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(true);
        for (long id : List.of(11L, 22L, 33L)) {
            jdbc.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified)"
                            + " VALUES(?,?,?,'fixture-only',TRUE)",
                    id,
                    "actor_" + id,
                    "actor" + id + "@example.test");
            jdbc.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE"
                            + " code='ROLE_USER'",
                    id);
        }
        jdbc.update(
                "INSERT INTO user_roles(user_id,role_id) SELECT 33,id FROM roles WHERE"
                        + " code='ROLE_ADMIN'");
        jdbc.update(
                "INSERT INTO restaurants(id,name,category_code)"
                        + " VALUES(1,'测试餐厅','OTHER'),(2,'测试食堂','OTHER')");
        jdbc.update(
                "UPDATE restaurants SET restaurant_type='CANTEEN',category_code=NULL WHERE id=2");
        jdbc.update(
                "INSERT INTO canteen_windows(id,canteen_id,name,floor_number,created_by)"
                        + " VALUES(1,2,'测试窗口',1,33)");
        properties.setEnabled(true);
        statistics.maintain();
        redisDates.add(date());
    }

    @AfterEach
    void cleanKeys() throws Exception {
        properties.setEnabled(false);
        clearRedis();
    }

    void clearRedis() throws Exception {
        var config =
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                        "127.0.0.1", Integer.parseInt(required("ACTIVITY_TEST_REDIS_PORT")));
        var factory =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                        config);
        factory.afterPropertiesSet();
        try {
            var template = new org.springframework.data.redis.core.StringRedisTemplate(factory);
            for (LocalDate day : redisDates) {
                template.delete(ActivityRedisStore.key(day));
            }
        } finally {
            factory.destroy();
        }
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (database == null || !database.matches("foodmap_activity_backend_it_[a-f0-9]{32}")) {
            return;
        }
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void permissionsAndDisabledState() throws Exception {
        mvc.perform(get("/api/admin/activity-statistics")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/me/activity")).andExpect(status().isUnauthorized());
        call(false, "/api/admin/activity-statistics", 11, null).andExpect(status().isForbidden());
        call(true, "/api/me/activity", 33, null).andExpect(status().isForbidden());
        call(true, "/api/me/activity", 11, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true));
        assertThat(redis.count(date())).isEqualTo(1);
        call(false, "/api/admin/activity-statistics", 33, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.days[0].collectionStatus").value("PARTIAL"));
        properties.setEnabled(false);
        call(true, "/api/me/activity", 22, null).andExpect(status().isOk());
        assertThat(redis.count(date())).isEqualTo(1);
        jdbc.update(
                "DELETE FROM app_metadata WHERE metadata_key=?", ActivityStatisticsService.STARTED);
        call(false, "/api/admin/activity-statistics", 33, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.days").isEmpty());
    }

    @Test
    void concurrentActiveIsUniqueAndSevenDaysUseUnion() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                tasks.add(pool.submit(() -> statistics.recordActive(11)));
            }
            for (var task : tasks) {
                task.get();
            }
        }
        statistics.recordActive(22);
        LocalDate yesterday = date().minusDays(1);
        Instant earlierStart = yesterday.atStartOfDay(ActivityRedisStore.ZONE).toInstant();
        writer.saveMetadata(ActivityStatisticsService.STARTED, earlierStart.toString());
        ReflectionTestUtils.setField(statistics, "started", earlierStart);
        redisDates.add(yesterday);
        redis.record(yesterday, 11);
        redis.record(yesterday, 33);
        now = now.plusSeconds(1);
        statistics.maintain();
        assertThat(mapper.day(date()).getActiveUserCount()).isEqualTo(2);
        assertThat(statistics.read().getSevenDayActiveUserCount()).isEqualTo(3);
        assertThat(statistics.read().getSevenDayCollectionStatus()).isEqualTo("PARTIAL");
    }

    @Test
    void registerAndDuplicateOnlyCountOnceEvenWhenLoginFails() throws Exception {
        var request =
                Map.of(
                        "username",
                        "new_actor",
                        "email",
                        "new_actor@std.uestc.edu.cn",
                        "password",
                        "Foodmap2026",
                        "verificationCode",
                        "123456");
        mvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(request)))
                .andExpect(status().isCreated());
        mvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict());
        assertThat(mapper.day(date()).getNewUserCount()).isEqualTo(1);
        var another = new LinkedHashMap<>(request);
        another.put("username", "login_failed_actor");
        another.put("email", "login_failed_actor@std.uestc.edu.cn");
        when(login.login(any()))
                .thenThrow(
                        new com.foodmap.common.exception.BusinessException(
                                com.foodmap.security.exception.AuthenticationErrorCode
                                        .SERVICE_UNAVAILABLE));
        mvc.perform(
                        post("/auth/register-and-login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(another)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_REGISTER_LOGIN_FAILED"));
        assertThat(mapper.day(date()).getNewUserCount()).isEqualTo(2);
    }

    @Test
    void publishesRestaurantsWindowsReplaysAndDeletionWithoutRecounting() throws Exception {
        var input = review();
        String id =
                data(call(true, "/api/restaurants/1/reviews", 11, input)
                                .andExpect(status().isCreated()))
                        .get("reviewId")
                        .asText();
        call(true, "/api/restaurants/1/reviews", 11, input).andExpect(status().isOk());
        call(true, "/api/canteen-windows/1/reviews", 11, review()).andExpect(status().isOk());
        call(true, "/api/restaurants/1/reviews", 33, review()).andExpect(status().isCreated());
        mvc.perform(delete("/api/reviews/" + id).header("Authorization", "Bearer " + token(11)))
                .andExpect(status().isNoContent());
        assertThat(mapper.day(date()).getPublishedReviewCount()).isEqualTo(2);
        String evidence = System.getenv("ACTIVITY_TEST_EVIDENCE_DIR");
        if (evidence != null) {
            var response =
                    call(false, "/api/admin/activity-statistics", 33, null)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(evidence, "actual-api-snapshot.json"), response);
        }
        var invalid = review();
        invalid.put("photoIds", List.of("9999"));
        call(true, "/api/restaurants/1/reviews", 11, invalid)
                .andExpect(status().is4xxClientError());
        assertThat(mapper.day(date()).getPublishedReviewCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void attachedReviewCountsAfterWholeApprovalAndUsesOriginalAuthor(boolean linked)
            throws Exception {
        var shop = new LinkedHashMap<String, Object>();
        shop.put("clientRequestId", UUID.randomUUID().toString());
        shop.put("name", "新增测试店");
        shop.put("categoryCode", "OTHER");
        shop.put("priceKind", "UNKNOWN");
        shop.put("latitude", 30.7544);
        shop.put("longitude", 103.9312);
        shop.put("locationConfirmed", true);
        var attached = review();
        attached.remove("clientRequestId");
        shop.put("review", attached);
        String id =
                data(call(true, "/api/restaurant-submissions", 11, shop)
                                .andExpect(status().isCreated()))
                        .get("id")
                        .asText();
        assertThat(mapper.day(date()).getPublishedReviewCount()).isZero();
        var approval = new LinkedHashMap<>(shop);
        approval.remove("clientRequestId");
        approval.remove("review");
        String path =
                "/api/admin/restaurant-requests/" + id + (linked ? "/link-existing" : "/approve");
        Object body = linked ? Map.of("restaurantId", "1") : approval;
        doThrow(new IllegalStateException("fixture rollback")).when(notices).insert(any());
        call(true, path, 33, body).andExpect(status().isInternalServerError());
        assertThat(mapper.day(date()).getPublishedReviewCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews", Long.class)).isZero();
        reset(notices);
        call(true, path, 33, body).andExpect(status().isOk());
        call(true, path, 33, body).andExpect(status().isOk());
        assertThat(mapper.day(date()).getPublishedReviewCount()).isEqualTo(1);
    }

    @Test
    void incrementFailureDoesNotFailPublicationOrBlindlyRetry() throws Exception {
        doThrow(new org.springframework.dao.QueryTimeoutException("fixture"))
                .when(summaries)
                .incrementReviews(any());
        var request = review();
        call(true, "/api/restaurants/1/reviews", 11, request).andExpect(status().isCreated());
        call(true, "/api/restaurants/1/reviews", 11, request).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews", Long.class)).isEqualTo(1);
        assertThat(mapper.day(date()).getCollectionStatus()).isEqualTo("PARTIAL");
        verify(summaries, times(1)).incrementReviews(any());
    }

    @Test
    void missingRedisSetDoesNotOverwriteSavedCountAndUnionBecomesUnknown() throws Exception {
        statistics.recordActive(11);
        now = now.plusSeconds(1);
        statistics.maintain();
        clearRedis();
        now = now.plusSeconds(1);
        statistics.maintain();
        assertThat(mapper.day(date()).getActiveUserCount()).isEqualTo(1);
        assertThat(mapper.day(date()).getCollectionStatus()).isEqualTo("PARTIAL");
        assertThat(statistics.read().getSevenDayActiveUserCount()).isNull();
    }

    @Test
    void staleSyncCannotOverwriteCountersAndRestartKeepsStart() {
        var original = statistics.read().getStartedAt();
        statistics.recordActive(11);
        now = now.plusSeconds(1);
        statistics.maintain();
        writer.synchronizeActive(date(), 0, now.minusSeconds(1));
        assertThat(mapper.day(date()).getActiveUserCount()).isEqualTo(1);
        ReflectionTestUtils.setField(statistics, "initialized", false);
        now = now.plusSeconds(180);
        statistics.maintain();
        assertThat(statistics.read().getStartedAt()).isEqualTo(original);
        assertThat(mapper.day(date()).getCollectionStatus()).isEqualTo("PARTIAL");
    }

    @Test
    void businessDateIsBeijingDateAndLateCallbacksKeepIt() {
        Instant before = now;
        LocalDate originalDate = date();
        now = originalDate.plusDays(1).atStartOfDay(ActivityRedisStore.ZONE).toInstant();
        statistics.maintain();
        statistics.contribution(before, true, true);
        assertThat(mapper.day(originalDate).getNewUserCount()).isEqualTo(1);
        assertThat(mapper.day(date()).getNewUserCount()).isNull();
    }

    @Test
    void redisRuntimeChangePersistsPartialAndFailureReturnsUnknownUnion() {
        var original = redis.fingerprint();
        doReturn("f".repeat(40) + ":1").when(redis).fingerprint();
        now = now.plusSeconds(1);
        statistics.maintain();
        assertThat(mapper.day(date()).getCollectionStatus()).isEqualTo("PARTIAL");
        doThrow(new org.springframework.dao.QueryTimeoutException("fixture"))
                .when(redis)
                .union(any(), any());
        assertThat(statistics.read().getSevenDayActiveUserCount()).isNull();
        doReturn(original).when(redis).fingerprint();
        now = now.plusSeconds(1);
        statistics.maintain();
        assertThat(mapper.day(date()).getCollectionStatus()).isEqualTo("PARTIAL");
    }

    @Test
    void retentionUsesOneCalendarYearAndKeepsBoundary() {
        LocalDate boundary = date().minusYears(1);
        for (LocalDate day : List.of(boundary.minusDays(1), boundary)) {
            var row = new com.foodmap.statistics.entity.ActivityDailySummary();
            row.setStatDate(day);
            row.setCollectionStatus("UNKNOWN");
            writer.createDay(row);
        }
        now = now.plusSeconds(1);
        statistics.maintain();
        assertThat(mapper.day(boundary.minusDays(1))).isNull();
        assertThat(mapper.day(boundary)).isNotNull();
    }

    @Test
    void nextContinuousDayInitializesTrueZerosAndIgnoresBeforeStartSets() {
        assertThat(mapper.day(date()).getNewUserCount()).isZero();
        LocalDate yesterday = date().minusDays(1);
        redisDates.add(yesterday);
        redis.record(yesterday, 22);
        assertThat(statistics.read().getSevenDayActiveUserCount()).isZero();
        now = date().plusDays(1).atStartOfDay(ActivityRedisStore.ZONE).toInstant();
        ReflectionTestUtils.setField(statistics, "lastObserved", now.minusSeconds(30));
        statistics.maintain();
        assertThat(mapper.day(date()).getCollectionStatus()).isEqualTo("NORMAL");
        assertThat(mapper.day(date()).getActiveUserCount()).isZero();
        assertThat(mapper.day(date()).getNewUserCount()).isZero();
        assertThat(mapper.day(date()).getPublishedReviewCount()).isZero();
    }

    private Map<String, Object> review() {
        return new LinkedHashMap<>(
                Map.of(
                        "clientRequestId",
                        UUID.randomUUID().toString(),
                        "scores",
                        Map.of("taste", 8, "value", 7, "service", 6, "ambience", 5),
                        "recommendation",
                        "NEUTRAL",
                        "body",
                        "这是一条隔离测试点评正文"));
    }

    private ResultActions call(boolean write, String path, long actor, Object body)
            throws Exception {
        var request = write ? post(path) : get(path);
        request.header("Authorization", "Bearer " + token(actor));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private tools.jackson.databind.JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private LocalDate date() {
        return now.atZone(ActivityRedisStore.ZONE).toLocalDate();
    }

    private String token(long user) {
        var at = Instant.now();
        var p = authentication.getJwt();
        var claims =
                JwtClaimsSet.builder()
                        .issuer(p.getIssuer())
                        .audience(List.of(p.getAudience()))
                        .subject(Long.toString(user))
                        .issuedAt(at.minusSeconds(30))
                        .expiresAt(at.plusSeconds(600))
                        .id(UUID.randomUUID().toString())
                        .claim("credential_version", 0)
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private static String required(String key) {
        return Objects.requireNonNull(System.getenv(key), "Set isolated test configuration");
    }

    private static String url(String name) {
        String server = required("ACTIVITY_TEST_DB_SERVER");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")
                || server.contains(":3306/")) {
            throw new IllegalArgumentException("Isolated loopback MySQL required");
        }
        return server
                + name
                + "?serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(
                url(name), "root", required("ACTIVITY_TEST_DB_PASSWORD"));
    }
}
