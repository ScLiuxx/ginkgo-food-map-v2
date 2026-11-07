package com.ginkgo.foodmap.community;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ginkgo.foodmap.notification.mapper.NotificationMapper;
import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 真正通过JWT安全链和MySQL验证关注、隐私及并发幂等，仅替换Redis会话并使用随机隔离库。 */
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
@EnabledIfSystemProperty(named = "follows.integration", matches = "true")
class FollowApiIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @MockitoBean LoginSessionService sessions;
    @MockitoSpyBean NotificationMapper messages;
    private String owner, other;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_follows_api_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", FollowApiIntegrationTest::username);
        registry.add("spring.datasource.password", FollowApiIntegrationTest::password);
    }

    @BeforeEach
    void setup() {
        reset(sessions, messages);
        db.update("DELETE FROM user_follows");
        db.update("DELETE FROM notification_messages");
        db.update("DELETE FROM restaurant_favorites");
        db.update("DELETE FROM restaurants");
        db.update("DELETE FROM user_roles");
        db.update("DELETE FROM users");
        for (long id : List.of(11L, 22L, 33L)) {
            db.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified,bio) VALUES(?,?,?,'fixture-hash',TRUE,'简介')",
                    id,
                    "favorite_" + id,
                    id + "@example.test");
            db.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ROLE_USER'",
                    id);
        }
        db.update(
                "INSERT INTO restaurants(id,name,category_code) VALUES(1,'普通店','OTHER'),(3,'保留历史的失效店','OTHER')");
        db.update(
                "INSERT INTO restaurants(id,name,category_code,restaurant_type) VALUES(2,'食堂',NULL,'CANTEEN')");
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(true);
        owner = token(11);
        other = token(22);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (!created) {
            return;
        }
        if (!database.matches("ginkgo_follows_api_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test database");
        }
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void allEndpointsRequireLoginAndSelfFollowIsRejected() throws Exception {
        for (String path :
                List.of(
                        "/api/users/22/social",
                        "/api/users/22/following",
                        "/api/users/22/followers",
                        "/api/me/follows/privacy",
                        "/api/me/notifications?category=FOLLOWERS")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(put("/api/users/22/follow")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/users/22/follow")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/users/11/follow").header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FOLLOW_SELF_FORBIDDEN"));
        for (String id : List.of("0", "01", "abc", "9223372036854775808")) {
            mvc.perform(
                            put("/api/users/" + id + "/follow")
                                    .header("Authorization", "Bearer " + owner))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void repeatedFollowAndRefollowKeepFirstNotificationAndReadTime() throws Exception {
        follow(22, owner);
        var original = db.queryForMap("SELECT * FROM user_follows");
        var notification = db.queryForObject("SELECT id FROM notification_messages", Long.class);
        mvc.perform(
                        post("/api/me/notifications/" + notification + "/read")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isNoContent());
        var saved = db.queryForMap("SELECT * FROM notification_messages");
        follow(22, owner);
        assertThat(db.queryForMap("SELECT * FROM user_follows")).isEqualTo(original);
        cancel(22, owner);
        follow(22, owner);
        assertThat(db.queryForObject("SELECT id FROM user_follows", Long.class))
                .isEqualTo(((Number) original.get("id")).longValue());
        assertThat(db.queryForObject("SELECT created_at FROM user_follows", Timestamp.class))
                .isEqualTo(original.get("created_at"));
        assertThat(db.queryForMap("SELECT * FROM notification_messages")).isEqualTo(saved);
        mvc.perform(get("/api/users/22/social").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followersCount").value(1))
                .andExpect(jsonPath("$.data.following").value(true));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM growth_rewards", Long.class)).isZero();
    }

    @Test
    void cancelWithoutFollowingDoesNotConsumeFirstNotificationEligibility() throws Exception {
        cancel(22, owner);
        cancel(22, owner);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_follows", Long.class)).isZero();
        follow(22, owner);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_messages", Long.class))
                .isEqualTo(1);
    }

    @Test
    void noticeInsertionFailureRollsBackBothNoticeAndRelation() throws Exception {
        doAnswer(
                        invocation -> {
                            var row =
                                    (com.ginkgo.foodmap.notification.entity.NotificationEntity)
                                            invocation.getArgument(0);
                            db.update(
                                    "INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,actor_user_id) VALUES(?,'USER_FOLLOWED',?,?)",
                                    row.getRecipientUserId(),
                                    row.getSourceId(),
                                    row.getActorUserId());
                            throw new org.springframework.dao.DataAccessResourceFailureException(
                                    "Injected after real notification insert");
                        })
                .when(messages)
                .insertFollow(any());
        mvc.perform(put("/api/users/22/follow").header("Authorization", "Bearer " + owner))
                .andExpect(status().is5xxServerError());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_follows", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_messages", Long.class))
                .isZero();
        reset(messages);
        follow(22, owner);
    }

    @Test
    void privateListsStillExposeRealCountsAndIndependentPrivacyControls() throws Exception {
        follow(22, owner);
        follow(11, other);
        mvc.perform(get("/api/users/11/following").header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users/11/social").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followingCount").value(1))
                .andExpect(jsonPath("$.data.followersCount").value(1));
        privacy(true, false);
        mvc.perform(get("/api/users/11/following").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].userId").value("22"));
        mvc.perform(get("/api/users/11/followers").header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
        privacy(false, true);
        mvc.perform(get("/api/users/11/following").header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users/11/followers").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/11/following").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
    }

    @Test
    void paginationIdentityValidationAndInactiveStateAreConsistent() throws Exception {
        follow(22, owner);
        follow(33, owner);
        mvc.perform(
                        get("/api/users/11/following?pageSize=1&page=2&userId=22")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("11"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].userId").value("22"));
        cancel(22, owner);
        mvc.perform(get("/api/users/11/following").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(
                        get("/api/users/11/following?page=2147483647&pageSize=50")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(
                        get("/api/users/11/following?pageSize=51")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/users/999/follow").header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        db.update("UPDATE users SET status='DISABLED' WHERE id=33");
        mvc.perform(put("/api/users/33/follow").header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        cancel(33, owner);
    }

    @Test
    void privacyStrictJsonAndOtherUserFieldsCannotChangeIdentity() throws Exception {
        for (String body :
                List.of(
                        "{}",
                        "{\"followingPublic\":true}",
                        "{\"followingPublic\":1,\"followersPublic\":false}",
                        "{\"followingPublic\":true,\"followersPublic\":false,\"userId\":22}")) {
            mvc.perform(
                            patch("/api/me/follows/privacy")
                                    .header("Authorization", "Bearer " + owner)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        var before =
                db.queryForMap(
                        "SELECT credential_version,bio,password_hash,favorites_public FROM users WHERE id=11");
        privacy(true, true);
        var version = db.queryForObject("SELECT row_version FROM users WHERE id=11", Long.class);
        privacy(true, true);
        assertThat(db.queryForObject("SELECT row_version FROM users WHERE id=11", Long.class))
                .isEqualTo(version);
        assertThat(
                        db.queryForMap(
                                "SELECT credential_version,bio,password_hash,favorites_public FROM users WHERE id=11"))
                .isEqualTo(before);
        mvc.perform(
                        get("/api/me/follows/privacy?userId=22")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("11"));
    }

    @Test
    void followersInboxSupportsCategoryCountsReadAllAndOwnership() throws Exception {
        follow(22, owner);
        mvc.perform(
                        get("/api/me/notifications?category=FOLLOWERS")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.unreadCounts.FOLLOWERS").value(1))
                .andExpect(jsonPath("$.data.records[0].category").value("FOLLOWERS"))
                .andExpect(jsonPath("$.data.records[0].actorUserId").value("11"))
                .andExpect(jsonPath("$.data.records[0].reviewId").isEmpty());
        var message = db.queryForObject("SELECT id FROM notification_messages", Long.class);
        mvc.perform(
                        post("/api/me/notifications/" + message + "/read")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/me/notifications/read-all?category=SYSTEM")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isNoContent());
        assertThat(db.queryForObject("SELECT read_at FROM notification_messages", Timestamp.class))
                .isNull();
        mvc.perform(
                        post("/api/me/notifications/read-all?category=FOLLOWERS")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me/notifications").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(0));
    }

    @Test
    void concurrentDuplicateAndMutualFollowsDoNotDuplicateNotifications() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        try {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 6; i++) {
                boolean reverse = i % 2 == 1;
                jobs.add(
                        pool.submit(
                                () -> {
                                    try {
                                        follow(reverse ? 11 : 22, reverse ? other : owner);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                }));
            }
            for (var job : jobs) {
                job.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_follows", Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_messages", Long.class))
                .isEqualTo(2);
    }

    /** 非空V32升级只扩展字段约束，保留旧消息及首次已读时间，不补发关注消息。 */
    @Test
    void v33UpgradePreservesExistingMessagesAndChecksFollowPayload() throws Exception {
        String name = "ginkgo_follows_api_it_" + UUID.randomUUID().toString().replace("-", "");
        boolean made = false;
        try {
            try (var c = connect("");
                    var statement = c.createStatement()) {
                statement.executeUpdate(
                        "CREATE DATABASE `"
                                + name
                                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                made = true;
            }
            var data =
                    new org.springframework.jdbc.core.JdbcTemplate(
                            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                    url(name), username(), password()));
            org.flywaydb.core.Flyway.configure()
                    .dataSource(url(name), username(), password())
                    .locations("classpath:db/migration")
                    .target("32")
                    .load()
                    .migrate();
            data.update(
                    "INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,request_id,request_type,decision,restaurant_name) VALUES(11,'RESTAURANT_DECISION',91,91,'CREATE','APPROVED','旧店名')");
            data.update(
                    "INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,review_id,actor_user_id) VALUES(11,'REVIEW_REPLY',92,7,22),(11,'REVIEW_LIKED',93,7,22)");
            data.update(
                    "UPDATE notification_messages SET read_at=GREATEST(created_at,CURRENT_TIMESTAMP(6)) WHERE source_id=92");
            var before = data.queryForList("SELECT * FROM notification_messages ORDER BY id");
            var flyway =
                    org.flywaydb.core.Flyway.configure()
                            .dataSource(url(name), username(), password())
                            .locations("classpath:db/migration")
                            .target("33")
                            .load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(data.queryForList("SELECT * FROM notification_messages ORDER BY id"))
                    .isEqualTo(before);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            data.update(
                    "INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,actor_user_id) VALUES(11,'USER_FOLLOWED',94,22)");
            for (String sql :
                    List.of(
                            "INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,actor_user_id,review_id) VALUES(11,'USER_FOLLOWED',95,22,7)",
                            "INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,actor_user_id) VALUES(11,'USER_FOLLOWED',96,11)")) {
                try {
                    data.update(sql);
                    fail("Expected follow payload constraint");
                } catch (org.springframework.dao.DataAccessException exception) {
                    assertThat(exception.getMostSpecificCause()).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) exception.getMostSpecificCause()).getErrorCode())
                            .isEqualTo(3819);
                }
            }
        } finally {
            if (made) {
                if (!name.matches("ginkgo_follows_api_it_[a-f0-9]{32}")) {
                    throw new IllegalStateException("Unexpected schema");
                }
                try (var c = connect("");
                        var statement = c.createStatement()) {
                    statement.executeUpdate("DROP DATABASE `" + name + "`");
                }
            }
        }
    }

    private void follow(long id, String token) throws Exception {
        mvc.perform(put("/api/users/" + id + "/follow").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.following").value(true));
    }

    private void cancel(long id, String token) throws Exception {
        mvc.perform(
                        delete("/api/users/" + id + "/follow")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.following").value(false));
    }

    private void privacy(boolean following, boolean followers) throws Exception {
        mvc.perform(
                        patch("/api/me/follows/privacy")
                                .header("Authorization", "Bearer " + owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"followingPublic\":"
                                                + following
                                                + ",\"followersPublic\":"
                                                + followers
                                                + "}"))
                .andExpect(status().isOk());
    }

    private String token(long id) {
        var now = Instant.now();
        var jwt = authentication.getJwt();
        var claims =
                JwtClaimsSet.builder()
                        .issuer(jwt.getIssuer())
                        .audience(List.of(jwt.getAudience()))
                        .subject(Long.toString(id))
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
        return System.getenv().getOrDefault("FOLLOWS_TEST_DB_USERNAME", "root");
    }

    private static String password() {
        return Objects.requireNonNull(
                System.getenv("FOLLOWS_TEST_DB_PASSWORD"), "Set isolated test password");
    }

    private static String url(String name) {
        var server =
                System.getenv()
                        .getOrDefault("FOLLOWS_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Loopback only");
        }
        return server + name + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), username(), password());
    }
}
