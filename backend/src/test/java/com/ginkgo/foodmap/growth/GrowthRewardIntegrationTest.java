package com.ginkgo.foodmap.growth;

import static com.ginkgo.foodmap.growth.dto.GrowthRewardOutcome.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ginkgo.foodmap.growth.config.GrowthProperties;
import com.ginkgo.foodmap.growth.dto.FirstStoreReviewContribution;
import com.ginkgo.foodmap.growth.dto.GrowthRewardOutcome;
import com.ginkgo.foodmap.growth.mapper.GrowthAccountMapper;
import com.ginkgo.foodmap.growth.service.GrowthQueryService;
import com.ginkgo.foodmap.growth.service.GrowthRewardApplicationService;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 真实MySQL/Flyway/HTTP闭环；仅替换Redis会话校验，不绕过JWT或数据库账号状态。 */
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.sql.init.mode=never",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.datasource.hikari.minimum-idle=0"
        })
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@EnabledIfSystemProperty(named = "growth.integration", matches = "true")
class GrowthRewardIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @Autowired PlatformTransactionManager transactions;
    @Autowired GrowthProperties properties;
    @Autowired GrowthQueryService queries;
    @MockitoBean LoginSessionService sessions;
    @MockitoSpyBean GrowthRewardApplicationService growth;
    @MockitoSpyBean GrowthAccountMapper accounts;
    private GrowthRewardApplicationService target;
    private String owner;

    /** 独立随机测试库，明确拒绝远程实例；测试凭据来自环境且不输出。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_growth_api_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = connect("");
                var st = c.createStatement()) {
            st.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", GrowthRewardIntegrationTest::username);
        registry.add("spring.datasource.password", GrowthRewardIntegrationTest::password);
    }

    /** 每例清空自身临时库，成长记录先于账户清理，不修改本地业务库。 */
    @BeforeEach
    void setup() {
        target = AopTestUtils.getUltimateTargetObject(growth);
        reset(target, accounts, sessions);
        properties.setFirstReviewExperience(5);
        properties.setNewStoreExperience(20);
        properties.setCorrectionExperience(10);
        properties.setLevelThresholds(List.of(0L, 100L, 400L, 1000L));
        for (String table :
                List.of(
                        "growth_rewards",
                        "growth_accounts",
                        "restaurant_search_outbox",
                        "review_photos",
                        "review_replies",
                        "review_request_receipts",
                        "reviews",
                        "media_files",
                        "restaurant_review_statistics",
                        "restaurants",
                        "user_roles",
                        "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (long id : List.of(11L, 22L)) {
            jdbc.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified) VALUES(?,?,?,?,TRUE)",
                    id,
                    "growth_" + id,
                    "growth" + id + "@example.invalid",
                    "test-only");
            jdbc.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ROLE_USER'",
                    id);
        }
        jdbc.update(
                "INSERT INTO restaurants(id,name,category_code,created_by) VALUES(1,'成长测试店','OTHER',11),(2,'第二家店','OTHER',11)");
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(true);
        owner = token(11);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (!created) {
            return;
        }
        if (!database.matches("ginkgo_growth_api_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test database");
        }
        try (var c = connect("");
                var st = c.createStatement()) {
            st.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void queryRequiresCurrentIdentityAndDoesNotCreateAccount() throws Exception {
        mvc.perform(get("/api/me/growth")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/growth?userId=22").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.userId").value("11"))
                .andExpect(jsonPath("$.data.experience").value(0))
                .andExpect(jsonPath("$.data.level").value(1))
                .andExpect(jsonPath("$.data.nextLevelThreshold").value(100));
        assertThat(count("growth_accounts")).isZero();
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=11");
        mvc.perform(get("/api/me/growth").header("Authorization", "Bearer " + owner))
                .andExpect(status().isUnauthorized());
    }

    /** 请求重放和真实同店再评分别验证；普通删评不扣奖励，也不释放长期资格。 */
    @Test
    void publishingReplayingDeletingAndRepublishingOnlyRewardsOnce() throws Exception {
        var body = input(UUID.randomUUID().toString());
        long id = publishedId(1, body);
        publish(1, body).andExpect(status().isOk());
        long latest = publishedId(1, input(UUID.randomUUID().toString()));
        assertThat(count("reviews")).isEqualTo(2);
        assertThat(experience(11)).isEqualTo(5);
        assertThat(count("growth_rewards")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_id FROM growth_rewards", Long.class))
                .isEqualTo(id);
        mvc.perform(delete("/api/reviews/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/reviews/" + latest).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        publish(1, body).andExpect(status().isConflict());
        publishedId(1, input(UUID.randomUUID().toString()));
        assertThat(experience(11)).isEqualTo(5);
        publishedId(2, input(UUID.randomUUID().toString()));
        assertThat(experience(11)).isEqualTo(10);
        assertThat(queries.summary(22).getExperience()).isZero();
        assertBalanced(11);
    }

    @Test
    void concurrentPublicationsInTheSameAndDifferentStoresDoNotLoseOrDuplicateExperience()
            throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Integer>>();
            for (long shop : List.of(1L, 1L, 2L, 2L)) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return publish(shop, input(UUID.randomUUID().toString()))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            }
            start.countDown();
            for (var result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(201);
            }
        }
        assertThat(count("reviews")).isEqualTo(4);
        assertThat(count("growth_rewards")).isEqualTo(2);
        assertThat(experience(11)).isEqualTo(10);
        assertBalanced(11);
    }

    /** 在经验已增加后抛错，验证图片、评分、搜索版本/任务及成长记录一起回滚。 */
    @Test
    void failureAfterAwardRollsBackTheWholeReviewAndPhotoBinding() throws Exception {
        jdbc.update(
                "INSERT INTO media_files(id,uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,expires_at) VALUES(991,11,'test-bucket','growth/rollback.jpg','image/jpeg',100,10,10,UNHEX(REPEAT('00',32)),'TEMPORARY',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR))");
        var body = input(UUID.randomUUID().toString());
        body.put("photoIds", List.of("991"));
        doAnswer(
                        invocation -> {
                            invocation.callRealMethod();
                            throw new IllegalStateException("injected after award");
                        })
                .when(target)
                .grantFirstStoreReview(any());
        publish(1, body).andExpect(status().isInternalServerError());
        for (String table :
                List.of(
                        "reviews",
                        "review_request_receipts",
                        "review_photos",
                        "restaurant_review_statistics",
                        "restaurant_search_outbox",
                        "growth_rewards",
                        "growth_accounts")) {
            assertThat(count(table)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=991", String.class))
                .isEqualTo("TEMPORARY");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT search_projection_version FROM restaurants WHERE id=1",
                                Long.class))
                .isEqualTo(1);
        reset(target);
        publish(1, body).andExpect(status().isCreated());
        assertThat(experience(11)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT status FROM media_files WHERE id=991", String.class))
                .isEqualTo("BOUND");
    }

    @Test
    void failedAccountUpdateDoesNotLeaveAnOrphanReward() throws Exception {
        doReturn(0).when(accounts).increase(anyLong(), anyInt());
        publish(1, input(UUID.randomUUID().toString())).andExpect(status().isInternalServerError());
        assertThat(count("growth_rewards")).isZero();
        assertThat(count("growth_accounts")).isZero();
        assertThat(count("reviews")).isZero();
    }

    /** 原事务完成回滚后重新执行完整发评，不能只重试成长部分。 */
    @Test
    void transientLockFailureRetriesWholeTransactionWithoutDuplicateRewards() throws Exception {
        var attempts = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            var result = invocation.callRealMethod();
                            if (attempts.incrementAndGet() == 1) {
                                throw new CannotAcquireLockException("injected lock retry");
                            }
                            return result;
                        })
                .when(target)
                .grantFirstStoreReview(any());
        publish(1, input(UUID.randomUUID().toString())).andExpect(status().isCreated());
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(count("reviews")).isEqualTo(1);
        assertThat(count("growth_rewards")).isEqualTo(1);
        assertThat(experience(11)).isEqualTo(5);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT search_projection_version FROM restaurants WHERE id=1",
                                Long.class))
                .isEqualTo(2);
    }

    @Test
    void lockRetryExhaustionReturns503AndLeavesNoCommittedEffects() throws Exception {
        doAnswer(
                        invocation -> {
                            invocation.callRealMethod();
                            throw new CannotAcquireLockException("injected repeated lock failure");
                        })
                .when(target)
                .grantFirstStoreReview(any());
        publish(1, input(UUID.randomUUID().toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REVIEW_TEMPORARILY_UNAVAILABLE"));
        assertThat(count("growth_rewards")).isZero();
        assertThat(count("growth_accounts")).isZero();
        assertThat(count("reviews")).isZero();
        assertThat(count("restaurant_search_outbox")).isZero();
    }

    @Test
    void revocationUsesOriginalAmountAfterConfigurationChangesAndIsConcurrentIdempotent()
            throws Exception {
        publishedId(1, input(UUID.randomUUID().toString()));
        long reward = jdbc.queryForObject("SELECT id FROM growth_rewards", Long.class);
        properties.setFirstReviewExperience(10);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return tx(() -> growth.revoke(11, reward, 7001));
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return tx(() -> growth.revoke(11, reward, 7002));
                            });
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(REVOKED, ALREADY_REVOKED);
        }
        assertThat(experience(11)).isZero();
        assertThat(jdbc.queryForObject("SELECT amount FROM growth_rewards", Integer.class))
                .isEqualTo(5);
        assertThat(tx(() -> growth.grantFirstStoreReview(contribution(11, 1, 9999))))
                .isEqualTo(ALREADY_REVOKED);
        assertThat(tx(() -> growth.revoke(22, reward, 7003))).isEqualTo(NOT_FOUND);
        assertThat(count("growth_accounts")).isEqualTo(1);
        assertBalanced(11);
    }

    @Test
    void failedRevocationRollsBackStateAndRejectsCorruptBalances() throws Exception {
        publishedId(1, input(UUID.randomUUID().toString()));
        long reward = jdbc.queryForObject("SELECT id FROM growth_rewards", Long.class);
        doReturn(0).when(accounts).decrease(anyLong(), anyInt());
        assertThrows(IllegalStateException.class, () -> tx(() -> growth.revoke(11, reward, 7001)));
        assertThat(experience(11)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT status FROM growth_rewards", String.class))
                .isEqualTo("GRANTED");
        reset(accounts);
        jdbc.update("UPDATE growth_accounts SET experience=0 WHERE user_id=11");
        assertThrows(IllegalStateException.class, () -> tx(() -> growth.revoke(11, reward, 7001)));
        assertThat(jdbc.queryForObject("SELECT status FROM growth_rewards", String.class))
                .isEqualTo("GRANTED");
    }

    @Test
    void overflowRejectsPublicationInsteadOfWrappingExperience() throws Exception {
        jdbc.update("INSERT INTO growth_accounts(user_id,experience) VALUES(11,?)", Long.MAX_VALUE);
        publish(1, input(UUID.randomUUID().toString())).andExpect(status().isInternalServerError());
        assertThat(experience(11)).isEqualTo(Long.MAX_VALUE);
        assertThat(count("growth_rewards")).isZero();
        assertThat(count("reviews")).isZero();
    }

    @Test
    void levelRisesAndFallsWithNetExperienceAndHighestThresholdHasNoNextLevel() throws Exception {
        // 其他有效奖励构造合法起点，避免通过单独修改汇总伪造一致性。
        jdbc.update("INSERT INTO growth_accounts(user_id,experience) VALUES(11,95)");
        jdbc.update(
                "INSERT INTO growth_rewards(user_id,reward_type,business_key,source_type,source_id,amount) VALUES(11,'CORRECTION_APPROVED','baseline','RESTAURANT_REQUEST',999,95)");
        publishedId(1, input(UUID.randomUUID().toString()));
        mvc.perform(get("/api/me/growth").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.experience").value(100))
                .andExpect(jsonPath("$.data.level").value(2));
        long id =
                jdbc.queryForObject(
                        "SELECT id FROM growth_rewards WHERE reward_type='FIRST_STORE_REVIEW'",
                        Long.class);
        assertThat(tx(() -> growth.revoke(11, id, 7001))).isEqualTo(REVOKED);
        assertThat(queries.summary(11).getLevel()).isEqualTo(1);
        assertBalanced(11);
    }

    @Test
    void internalWritesRequireExplicitReadCommittedWritableTransaction() {
        assertThrows(
                IllegalTransactionStateException.class,
                () -> growth.grantFirstStoreReview(contribution(11, 1, 1)));
        var repeatable = new TransactionTemplate(transactions);
        repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(
                IllegalStateException.class,
                () ->
                        repeatable.execute(
                                status -> growth.grantFirstStoreReview(contribution(11, 1, 1))));
        var readonly = new TransactionTemplate(transactions);
        readonly.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        readonly.setReadOnly(true);
        assertThrows(
                IllegalStateException.class,
                () ->
                        readonly.execute(
                                status -> growth.grantFirstStoreReview(contribution(11, 1, 1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> tx(() -> growth.grantFirstStoreReview(contribution(0, 1, 1))));
        assertThat(count("growth_accounts")).isZero();
    }

    /** 同人同店的不同申请并发通过也只记一次；不同店铺与不同人分别保留资格。 */
    @Test
    void approvalRewardsSerializeConcurrentQualificationsAndRetainRevokedEligibility()
            throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var outcomes = new ArrayList<Future<GrowthRewardOutcome>>();
            for (long request : List.of(101L, 102L, 103L, 104L)) {
                outcomes.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return tx(
                                            () ->
                                                    growth.grantApprovedRequest(
                                                            11, 1, request, "UPDATE"));
                                }));
            }
            start.countDown();
            var results = new ArrayList<GrowthRewardOutcome>();
            for (var outcome : outcomes) {
                results.add(outcome.get(20, TimeUnit.SECONDS));
            }
            assertThat(results)
                    .containsExactlyInAnyOrder(
                            GRANTED, ALREADY_GRANTED, ALREADY_GRANTED, ALREADY_GRANTED);
        }
        long reward = jdbc.queryForObject("SELECT id FROM growth_rewards", Long.class);
        properties.setCorrectionExperience(30);
        assertThat(tx(() -> growth.revoke(11, reward, 7001))).isEqualTo(REVOKED);
        assertThat(tx(() -> growth.grantApprovedRequest(11, 1, 105, "UPDATE")))
                .isEqualTo(ALREADY_REVOKED);
        assertThat(experience(11)).isZero();
        assertThat(tx(() -> growth.grantApprovedRequest(11, 2, 106, "UPDATE"))).isEqualTo(GRANTED);
        assertThat(tx(() -> growth.grantApprovedRequest(22, 1, 107, "UPDATE"))).isEqualTo(GRANTED);
        assertThat(experience(11)).isEqualTo(30);
        assertThat(experience(22)).isEqualTo(30);
        assertBalanced(11);
        assertBalanced(22);
    }

    /** 审核写入口同样拒绝缺少事务、只读事务和客户端无法提供的非法贡献。 */
    @Test
    void approvalWritesRejectInvalidContributionsBeforeCreatingAccounts() {
        assertThrows(
                IllegalTransactionStateException.class,
                () -> growth.grantApprovedRequest(11, 1, 1, "CREATE"));
        var readonly = new TransactionTemplate(transactions);
        readonly.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        readonly.setReadOnly(true);
        assertThrows(
                IllegalStateException.class,
                () -> readonly.execute(status -> growth.grantApprovedRequest(11, 1, 1, "CREATE")));
        assertThrows(
                IllegalArgumentException.class,
                () -> tx(() -> growth.grantApprovedRequest(11, 1, 1, "DELETE")));
        assertThrows(
                IllegalArgumentException.class,
                () -> tx(() -> growth.grantApprovedRequest(11, 0, 1, "UPDATE")));
        assertThat(count("growth_accounts")).isZero();
    }

    private void assertBalanced(long user) {
        assertThat(experience(user))
                .isEqualTo(
                        jdbc.queryForObject(
                                "SELECT COALESCE(SUM(amount),0) FROM growth_rewards WHERE user_id=? AND status='GRANTED'",
                                Long.class,
                                user));
    }

    private long experience(long user) {
        return jdbc.queryForObject(
                "SELECT experience FROM growth_accounts WHERE user_id=?", Long.class, user);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private <T> T tx(Supplier<T> operation) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
        return transaction.execute(status -> operation.get());
    }

    private FirstStoreReviewContribution contribution(long user, long shop, long review) {
        var result = new FirstStoreReviewContribution();
        result.setUserId(user);
        result.setRestaurantId(shop);
        result.setReviewId(review);
        return result;
    }

    private Map<String, Object> input(String uuid) {
        return new LinkedHashMap<>(
                Map.of(
                        "clientRequestId",
                        uuid,
                        "scores",
                        Map.of("taste", 8, "value", 8, "service", 8, "ambience", 8),
                        "recommended",
                        true,
                        "body",
                        "首次点评成长闭环测试正文"));
    }

    private org.springframework.test.web.servlet.ResultActions publish(
            long shop, Map<String, Object> body) throws Exception {
        return mvc.perform(
                post("/api/restaurants/" + shop + "/reviews")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
    }

    private long publishedId(long shop, Map<String, Object> body) throws Exception {
        var response =
                publish(shop, body).andExpect(status().isCreated()).andReturn().getResponse();
        return Long.parseLong(
                json.readTree(response.getContentAsString()).get("data").get("reviewId").asText());
    }

    private String token(long user) {
        var now = Instant.now();
        var jwt = authentication.getJwt();
        var claims =
                JwtClaimsSet.builder()
                        .issuer(jwt.getIssuer())
                        .audience(List.of(jwt.getAudience()))
                        .subject(Long.toString(user))
                        .issuedAt(now.minusSeconds(60))
                        .expiresAt(now.plusSeconds(600))
                        .id(UUID.randomUUID().toString())
                        .claim("credential_version", 0)
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private static String username() {
        return System.getenv().getOrDefault("GROWTH_TEST_DB_USERNAME", "root");
    }

    private static String password() {
        return Objects.requireNonNull(
                System.getenv("GROWTH_TEST_DB_PASSWORD"), "Set isolated growth test password");
    }

    private static String url(String name) {
        String server =
                System.getenv()
                        .getOrDefault("GROWTH_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Loopback only");
        }
        return server
                + name
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), username(), password());
    }
}
