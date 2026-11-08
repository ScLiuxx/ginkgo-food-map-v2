package com.ginkgo.foodmap.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ginkgo.foodmap.notification.dto.ContentLikeNotification;
import com.ginkgo.foodmap.notification.dto.FollowNotification;
import com.ginkgo.foodmap.notification.dto.RestaurantDecisionNotification;
import com.ginkgo.foodmap.notification.dto.ReviewReplyNotification;
import com.ginkgo.foodmap.notification.mapper.NotificationMapper;
import com.ginkgo.foodmap.notification.service.NotificationPublicationService;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 真实MySQL随机隔离库验证收件箱删除、来源去重、读写竞争及迁移；仅会话使用替身。 */
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.sql.init.mode=never",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.datasource.hikari.minimum-idle=0"
        })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@EnabledIfSystemProperty(named = "review.integration", matches = "true")
class NotificationInboxIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @Autowired PlatformTransactionManager transactions;
    @Autowired NotificationPublicationService publications;
    @Autowired SqlSessionTemplate sql;
    @MockitoBean LoginSessionService sessions;
    @MockitoSpyBean NotificationMapper messages;
    @Autowired com.ginkgo.foodmap.notification.service.NotificationStreamService streams;
    private String owner;
    private String other;

    /** 开启集成测试仍必须显式提供回环地址，拒绝连接开发业务库。 */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_notification_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", NotificationInboxIntegrationTest::password);
    }

    @BeforeEach
    void setup() {
        reset(sessions, messages);
        jdbc.update("DELETE FROM notification_messages");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
        for (long user : List.of(11L, 22L)) {
            jdbc.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified)"
                            + " VALUES(?,?,?,?,TRUE)",
                    user,
                    "notice_" + user,
                    "notice" + user + "@example.test",
                    "test-only");
            jdbc.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",
                    user,
                    user == 11 ? "ROLE_USER" : "ROLE_ADMIN");
        }
        when(sessions.isActive(
                        org.mockito.ArgumentMatchers.anyString(),
                        anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        owner = token(11);
        other = token(22);
    }

    /** 只清理本次成功创建且名称匹配的临时库，不使用全局clean。 */
    @AfterAll
    static void cleanup() throws SQLException {
        if (!created) {
            return;
        }
        drop(database);
    }

    @Test
    void deletingUnreadItemsFiltersEveryCategoryAndLatestSummary() throws Exception {
        seed(101, "RESTAURANT_DECISION");
        seed(102, "RESTAURANT_DECISION");
        seed(103, "REVIEW_REPLY");
        seed(104, "REVIEW_LIKED");
        seed(105, "REPLY_LIKED");
        seed(106, "USER_FOLLOWED");
        inbox("ALL")
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.latestSystem.id").value("102"));
        for (long id : List.of(102L, 103L, 104L, 105L, 106L)) {
            remove(owner, id).andExpect(status().isNoContent());
        }
        inbox("ALL")
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.unreadCounts.COMMENTS").value(0))
                .andExpect(jsonPath("$.data.unreadCounts.LIKES").value(0))
                .andExpect(jsonPath("$.data.unreadCounts.FOLLOWERS").value(0))
                .andExpect(jsonPath("$.data.latestSystem.id").value("101"));
        for (String category : List.of("COMMENTS", "LIKES", "FOLLOWERS")) {
            inbox(category)
                    .andExpect(jsonPath("$.data.total").value(0))
                    .andExpect(jsonPath("$.data.records").isEmpty());
        }
        remove(owner, 101).andExpect(status().isNoContent());
        inbox("SYSTEM").andExpect(jsonPath("$.data.latestSystem").isEmpty());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_messages", Long.class))
                .isEqualTo(6);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification_messages WHERE read_at IS NOT"
                                        + " NULL",
                                Long.class))
                .isZero();
    }

    @Test
    void enforcesRecipientEvenForAdministratorAndDeletedReceipts() throws Exception {
        seed(101, "REVIEW_REPLY");
        mvc.perform(delete("/api/me/notifications/101")).andExpect(status().isUnauthorized());
        remove(other, 101).andExpect(status().isNotFound());
        remove(owner, 999).andExpect(status().isNotFound());
        for (String id : List.of("0", "01", "-1", "abc", "9223372036854775808")) {
            mvc.perform(
                            delete("/api/me/notifications/" + id)
                                    .header("Authorization", "Bearer " + owner))
                    .andExpect(status().isBadRequest());
        }
        remove(owner, 101)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
        var deletedAt =
                jdbc.queryForObject(
                        "SELECT deleted_at FROM notification_messages WHERE id=101",
                        java.sql.Timestamp.class);
        remove(owner, 101).andExpect(status().isNoContent());
        remove(other, 101).andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT deleted_at FROM notification_messages WHERE id=101",
                                java.sql.Timestamp.class))
                .isEqualTo(deletedAt);
    }

    @Test
    void deletingReadItemsPreservesReadTimeAndReadAllSkipsDeletedItems() throws Exception {
        seed(101, "REVIEW_REPLY");
        seed(102, "REVIEW_REPLY");
        seed(103, "REVIEW_REPLY");
        mvc.perform(
                        post("/api/me/notifications/101/read")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        var readAt =
                jdbc.queryForObject(
                        "SELECT read_at FROM notification_messages WHERE id=101",
                        java.sql.Timestamp.class);
        remove(owner, 101).andExpect(status().isNoContent());
        remove(owner, 102).andExpect(status().isNoContent());
        mvc.perform(
                        post("/api/me/notifications/102/read")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/me/notifications/read-all")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT read_at FROM notification_messages WHERE id=101",
                                java.sql.Timestamp.class))
                .isEqualTo(readAt);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT read_at FROM notification_messages WHERE id=102",
                                java.sql.Timestamp.class))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT read_at FROM notification_messages WHERE id=103",
                                java.sql.Timestamp.class))
                .isNotNull();
    }

    /** 所有现行来源在收件箱删除后仍能幂等发布；核验读取不能错误地过滤删除回执。 */
    @Test
    void sourceReplaysNeverRestoreDeletedNotifications() throws Exception {
        var decision = new RestaurantDecisionNotification();
        decision.setRecipientUserId(11);
        decision.setRequestId(101);
        decision.setRequestType("CREATE");
        decision.setDecision("APPROVED");
        decision.setRestaurantName("测试店");
        var reply = new ReviewReplyNotification();
        reply.setRecipientUserId(11);
        reply.setActorUserId(22);
        reply.setReplyId(102);
        reply.setReviewId(1);
        var like = new ContentLikeNotification();
        like.setRecipientUserId(11);
        like.setActorUserId(22);
        like.setLikeId(103);
        like.setReviewId(1);
        like.setTargetType("REVIEW");
        var replyLike = new ContentLikeNotification();
        replyLike.setRecipientUserId(11);
        replyLike.setActorUserId(22);
        replyLike.setLikeId(105);
        replyLike.setReviewId(1);
        replyLike.setTargetType("REPLY");
        var follow = new FollowNotification();
        follow.setRecipientUserId(11);
        follow.setActorUserId(22);
        follow.setRelationId(104);
        Runnable publish =
                () -> {
                    publications.restaurantDecision(decision);
                    publications.reviewReply(reply);
                    publications.contentLike(like);
                    publications.contentLike(replyLike);
                    publications.follow(follow);
                };
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> publish.run());
        for (long id : jdbc.queryForList("SELECT id FROM notification_messages", Long.class)) {
            remove(owner, id).andExpect(status().isNoContent());
        }
        var snapshot = jdbc.queryForList("SELECT * FROM notification_messages ORDER BY id");
        tx.executeWithoutResult(status -> publish.run());
        assertThat(jdbc.queryForList("SELECT * FROM notification_messages ORDER BY id"))
                .isEqualTo(snapshot);
        inbox("ALL").andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void concurrentDeletesAndReadsNeverRestoreVisibility() throws Exception {
        seed(101, "REVIEW_REPLY");
        try (var pool = Executors.newFixedThreadPool(3)) {
            var start = new CountDownLatch(1);
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return remove(owner, 101).andReturn().getResponse().getStatus();
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return remove(owner, 101).andReturn().getResponse().getStatus();
                            });
            var read =
                    pool.submit(
                            () -> {
                                start.await();
                                return mvc.perform(
                                                post("/api/me/notifications/101/read")
                                                        .header("Authorization", "Bearer " + owner))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(read.get(20, TimeUnit.SECONDS)).isIn(204, 404);
        }
        inbox("ALL").andExpect(jsonPath("$.data.total").value(0));
    }

    /** 在全部已读的快照选中ID之后删除，当前UPDATE条件仍应排除已删除行。 */
    @Test
    void readAllDoesNotWriteReadTimeAfterConcurrentDeletion() throws Exception {
        seed(101, "REVIEW_REPLY");
        seed(102, "REVIEW_REPLY");
        var selected = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        NotificationMapper delegate = sql.getMapper(NotificationMapper.class);
        doAnswer(
                        invocation -> {
                            selected.countDown();
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("timeout");
                            }
                            return delegate.markReadBatch(
                                    invocation.getArgument(0), invocation.getArgument(1));
                        })
                .when(messages)
                .markReadBatch(anyLong(), anyList());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var all =
                    pool.submit(
                            () ->
                                    mvc.perform(
                                                    post("/api/me/notifications/read-all")
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + owner))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            try {
                assertThat(selected.await(10, TimeUnit.SECONDS)).isTrue();
                remove(owner, 101).andExpect(status().isNoContent());
            } finally {
                release.countDown();
            }
            assertThat(all.get(20, TimeUnit.SECONDS)).isEqualTo(204);
        } finally {
            release.countDown();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT read_at FROM notification_messages WHERE id=101",
                                java.sql.Timestamp.class))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT read_at FROM notification_messages WHERE id=102",
                                java.sql.Timestamp.class))
                .isNotNull();
    }

    @Test
    void deletionFailureRollsBackVisibilityAndUnreadCounts() throws Exception {
        seed(101, "REVIEW_REPLY");
        NotificationMapper delegate = sql.getMapper(NotificationMapper.class);
        doAnswer(
                        invocation -> {
                            delegate.remove(11, 101);
                            throw new IllegalStateException("injected failure after update");
                        })
                .when(messages)
                .remove(11, 101);
        remove(owner, 101).andExpect(status().isInternalServerError());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT deleted_at FROM notification_messages WHERE id=101",
                                java.sql.Timestamp.class))
                .isNull();
        inbox("ALL")
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.unreadCount").value(1));
    }

    @Test
    void upgradesV34WithoutChangingMessagesAndRepeatedMigrationIsEmpty() throws Exception {
        String name = "ginkgo_notification_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE DATABASE `"
                            + name
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        try {
            Flyway.configure()
                    .dataSource(url(name), "root", password())
                    .locations("classpath:db/migration")
                    .target("34")
                    .load()
                    .migrate();
            var data =
                    new JdbcTemplate(
                            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                    url(name), "root", password()));
            data.update(
                    "INSERT INTO"
                        + " notification_messages(id,recipient_user_id,notification_type,source_id,actor_user_id,created_at,read_at)"
                        + " VALUES(101,11,'USER_FOLLOWED',3,22,'2026-11-01 00:00:00','2026-11-02"
                        + " 00:00:00')");
            var snapshot = data.queryForMap("SELECT * FROM notification_messages WHERE id=101");
            var flyway =
                    Flyway.configure()
                            .dataSource(url(name), "root", password())
                            .locations("classpath:db/migration")
                            .target("35")
                            .load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            flyway.validate();
            var after = data.queryForMap("SELECT * FROM notification_messages WHERE id=101");
            assertThat(after.remove("deleted_at")).isNull();
            assertThat(after).isEqualTo(snapshot);
            assertThat(
                            data.queryForObject(
                                    "SELECT COUNT(DISTINCT index_name) FROM"
                                            + " information_schema.statistics WHERE"
                                            + " table_schema=DATABASE() AND"
                                            + " table_name='notification_messages' AND"
                                            + " column_name='deleted_at'",
                                    Long.class))
                    .isEqualTo(4);
        } finally {
            drop(name);
        }
    }

    private void seed(long id, String type) {
        if ("RESTAURANT_DECISION".equals(type)) {
            jdbc.update(
                    "INSERT INTO"
                        + " notification_messages(id,recipient_user_id,notification_type,source_id,request_id,request_type,decision,restaurant_name,created_at)"
                        + " VALUES(?,11,?,?,?,'CREATE','APPROVED','测试店','2026-11-01 00:00:00')",
                    id,
                    type,
                    id,
                    id);
        } else {
            jdbc.update(
                    "INSERT INTO"
                        + " notification_messages(id,recipient_user_id,notification_type,source_id,review_id,actor_user_id,created_at)"
                        + " VALUES(?,11,?,?,?,22,'2026-11-01 00:00:00')",
                    id,
                    type,
                    id,
                    "USER_FOLLOWED".equals(type) ? null : 1);
        }
    }

    /** SSE真实鉴权/响应头及事务提交提示；已读后的轻量查询必须归零。 */
    @Test
    void realtimeStreamAndUnreadArePrivate() throws Exception {
        mvc.perform(get("/api/me/notifications/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/notifications/unread")).andExpect(status().isUnauthorized());
        var result =
                mvc.perform(
                                get("/api/me/notifications/events")
                                        .header("Authorization", "Bearer " + owner))
                        .andExpect(status().isOk())
                        .andExpect(header().string("X-Accel-Buffering", "no"))
                        .andReturn();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!result.getResponse().getContentAsString().contains("event:sync")
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(result.getResponse().getContentAsString()).contains("event:sync");
        streams.changed(22);
        Thread.sleep(100);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("notifications-changed");
        streams.changed(11);
        while (!result.getResponse().getContentAsString().contains("notifications-changed")
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(result.getResponse().getContentAsString()).contains("notifications-changed");
        mvc.perform(get("/api/me/notifications/unread").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("11"))
                .andExpect(jsonPath("$.data.unreadCount").value(0));
        when(sessions.isActive(org.mockito.ArgumentMatchers.anyString(), anyLong(), anyLong()))
                .thenReturn(false);
        streams.changed(11);
    }

    private ResultActions remove(String token, long id) throws Exception {
        return mvc.perform(
                delete("/api/me/notifications/" + id).header("Authorization", "Bearer " + token));
    }

    private ResultActions inbox(String category) throws Exception {
        return mvc.perform(
                        get("/api/me/notifications")
                                .param("category", category)
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
    }

    private String token(long user) {
        var jwt = authentication.getJwt();
        var now = Instant.now();
        var claims =
                JwtClaimsSet.builder()
                        .issuer(jwt.getIssuer())
                        .audience(List.of(jwt.getAudience()))
                        .subject(Long.toString(user))
                        .issuedAt(now.minusSeconds(30))
                        .expiresAt(now.plusSeconds(600))
                        .id(UUID.randomUUID().toString())
                        .claim("credential_version", 0)
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private static String password() {
        return System.getenv().getOrDefault("REVIEW_TEST_DB_PASSWORD", "");
    }

    private static String url(String name) {
        String server = System.getenv("REVIEW_TEST_DB_SERVER");
        if (server == null
                || !server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Expected explicit loopback test server");
        }
        return server
                + name
                + "?serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), "root", password());
    }

    private static void drop(String name) throws SQLException {
        if (!name.matches("ginkgo_notification_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test database");
        }
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + name + "`");
        }
    }
}
