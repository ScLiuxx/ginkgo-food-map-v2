package com.ginkgo.foodmap.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ginkgo.foodmap.security.TestJwtKeys;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginSessionService;
import com.ginkgo.foodmap.user.mapper.UserProfileMapper;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
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
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 真实MySQL/Flyway/RS256验证本人资料；只替换Redis会话，随机回环库不访问业务数据。 */
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
@EnabledIfSystemProperty(named = "profile.integration", matches = "true")
class UserProfileApiIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @MockitoBean LoginSessionService sessions;
    @MockitoSpyBean UserProfileMapper profiles;
    private String owner;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "ginkgo_profile_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", UserProfileApiIntegrationTest::username);
        registry.add("spring.datasource.password", UserProfileApiIntegrationTest::password);
    }

    @BeforeEach
    void setup() {
        reset(sessions, profiles);
        jdbc.update("DELETE FROM content_likes");
        jdbc.update("DELETE FROM growth_rewards");
        jdbc.update("DELETE FROM growth_accounts");
        jdbc.update("DELETE FROM review_photos");
        jdbc.update("DELETE FROM review_replies");
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM restaurant_review_statistics");
        jdbc.update("DELETE FROM restaurants");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
        for (long id : List.of(11L, 22L)) {
            jdbc.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified,bio) VALUES(?,?,?,?,TRUE,?)",
                    id,
                    "profile_" + id,
                    "private_local" + id + "@std.uestc.edu.cn",
                    "test-only-private-hash",
                    id == 11 ? "原有简介" : "其他账号的简介");
            jdbc.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ROLE_USER'",
                    id);
        }
        when(sessions.isActive(anyString(), anyLong(), anyLong())).thenReturn(true);
        owner = token(11);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (created) {
            drop(database);
        }
    }

    /** 登录用户能读他人主页，但任何私有账号/奖励/申请字段均不得随聚合响应泄露。 */
    @Test
    void publicProfileRequiresLoginAndReturnsOnlyPublicWhitelist() throws Exception {
        mvc.perform(get("/api/users/22/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/22/reviews")).andExpect(status().isUnauthorized());
        jdbc.update("INSERT INTO growth_accounts(user_id,experience) VALUES(22,400)");
        var result =
                mvc.perform(get("/api/users/22/profile").header("Authorization", "Bearer " + owner))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.userId").value("22"))
                        .andExpect(jsonPath("$.data.username").value("profile_22"))
                        .andExpect(jsonPath("$.data.bio").value("其他账号的简介"))
                        .andExpect(jsonPath("$.data.level").value(3))
                        .andExpect(jsonPath("$.data.receivedLikes").value(0))
                        .andReturn();
        var fields = json.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(fields.size()).isEqualTo(5);
        assertThat(fields.toString())
                .doesNotContain(
                        "email", "experience", "password", "roles", "credential", "request");
        for (String id : List.of("0", "01", "abc", "9223372036854775808")) {
            mvc.perform(
                            get("/api/users/" + id + "/profile")
                                    .header("Authorization", "Bearer " + owner))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/users/999/profile").header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/users/999/reviews").header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE users SET status='DELETED' WHERE id=22");
        mvc.perform(get("/api/users/22/profile").header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
    }

    /** 公开读取沿用可见店铺过滤；按主体而非访问者归组，页内没有投稿、邮箱及管理能力。 */
    @Test
    void publicReviewsGroupByVisibleStoreAndRetainHistoryAndAuthorLevel() throws Exception {
        jdbc.update(
                "INSERT INTO restaurants(id,name,category_code) VALUES(71,'主页店','OTHER'),(72,'隐藏店','OTHER')");
        jdbc.update(
                "UPDATE restaurants SET deleted_at=CURRENT_TIMESTAMP(6),deleted_by=11,delete_reason_code='ADMIN_REMOVAL' WHERE id=72");
        profileReview(101, 71, 22, "2026-11-01 00:00:00");
        profileReview(102, 71, 22, "2026-11-02 00:00:00");
        profileReview(103, 72, 22, "2026-11-03 00:00:00");
        profileReview(104, 71, 11, "2026-11-04 00:00:00");
        jdbc.update(
                "INSERT INTO content_likes(target_type,target_id,user_id) VALUES('REVIEW',102,11)");
        mvc.perform(
                        get("/api/users/22/reviews")
                                .param("userId", "11")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.author.id").value("22"))
                .andExpect(jsonPath("$.data.author.level").value(1))
                .andExpect(jsonPath("$.data.totalGroups").value(1))
                .andExpect(jsonPath("$.data.totalReviews").value(2))
                .andExpect(jsonPath("$.data.records[0].currentReview.id").value("102"))
                .andExpect(jsonPath("$.data.records[0].historyCount").value(1))
                .andExpect(jsonPath("$.data.records[0].nextHistoryCursor").isString());
        mvc.perform(get("/api/users/22/profile").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedLikes").value(1));
        jdbc.update("UPDATE content_likes SET active=0");
        mvc.perform(get("/api/users/22/profile").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedLikes").value(0));
        // 停用不隐藏正常历史内容；访问者自身仍须通过正常登录鉴权。
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=22");
        mvc.perform(get("/api/users/22/reviews").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalGroups").value(1));
    }

    private void profileReview(long id, long restaurant, long author, String time) {
        jdbc.update(
                "INSERT INTO reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash,created_at) VALUES(?,?,?,8,8,8,8,TRUE,'主页公开点评正文',UUID_TO_BIN(?),UNHEX(SHA2('profile',256)),?)",
                id,
                restaurant,
                author,
                UUID.randomUUID().toString(),
                time);
    }

    @Test
    void queryUsesCurrentIdentityAndOnlyReturnsMaskedWhitelist() throws Exception {
        mvc.perform(get("/api/me/profile")).andExpect(status().isUnauthorized());
        var before = account(11);
        var response =
                mvc.perform(
                                get("/api/me/profile?userId=22")
                                        .header("Authorization", "Bearer " + owner))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.userId").value("11"))
                        .andExpect(jsonPath("$.data.username").value("profile_11"))
                        .andExpect(jsonPath("$.data.maskedEmail").value("p***@std.uestc.edu.cn"))
                        .andExpect(jsonPath("$.data.bio").value("原有简介"))
                        .andReturn();
        assertThat(response.getResponse().getContentAsString())
                .doesNotContain(
                        "private_local",
                        "test-only-private-hash",
                        "credentialVersion",
                        "rowVersion",
                        "roles",
                        "studentVerification",
                        "\"email\"");
        assertThat(account(11)).isEqualTo(before);
        verify(profiles, never()).updateBio(anyLong(), anyString());
        mvc.perform(get("/api/me/profile").header("Authorization", "Bearer " + token(22)))
                .andExpect(jsonPath("$.data.bio").value("其他账号的简介"));
    }

    @Test
    void savesOnlyBioAndKeepsCredentialEmailRolesAndOtherAccountUnchanged() throws Exception {
        var before = account(11);
        var other = account(22);
        String input = "\u00a0爱吃１号面馆👨‍👩‍👧\r\n也喜欢QQ糖\u00a0";
        String expected = "爱吃１号面馆👨‍👩‍👧\n也喜欢QQ糖";
        mvc.perform(
                        patch("/api/me/profile?userId=22")
                                .header("Authorization", "Bearer " + owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("bio", input))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.userId").value("11"))
                .andExpect(jsonPath("$.data.bio").value(expected));
        var after = account(11);
        for (String field :
                List.of(
                        "username",
                        "email",
                        "password_hash",
                        "email_verified",
                        "credential_version",
                        "status",
                        "student_verification_status",
                        "created_at")) {
            assertThat(after.get(field)).as(field).isEqualTo(before.get(field));
        }
        assertThat(((Number) after.get("row_version")).longValue())
                .isEqualTo(((Number) before.get("row_version")).longValue() + 1);
        assertThat(after.get("bio")).isEqualTo(expected);
        assertThat(account(22)).isEqualTo(other);
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
    }

    @Test
    void supportsOneHundredEmojiAndRejectsOneHundredAndOneWithoutTruncation() throws Exception {
        update("🥟".repeat(100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bio").value("🥟".repeat(100)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT CHAR_LENGTH(bio) FROM users WHERE id=11", Integer.class))
                .isEqualTo(100);
        var before = account(11);
        update("🥟".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_BIO_TOO_LONG"));
        assertThat(account(11)).isEqualTo(before);
    }

    @Test
    void repeatAndClearAreIdempotentAndDoNotInventVersionRequirements() throws Exception {
        update("新简介").andExpect(status().isOk());
        var before = account(11);
        update(" 新简介 ").andExpect(status().isOk());
        assertThat(account(11)).isEqualTo(before);
        verify(profiles, times(1)).updateBio(11, "新简介");
        update("  \n\t ").andExpect(status().isOk()).andExpect(jsonPath("$.data.bio").value(""));
        assertThat(jdbc.queryForObject("SELECT bio FROM users WHERE id=11", String.class))
                .isEmpty();
    }

    @Test
    void rejectsImmutableExtraFieldsWrongTypesAndMissingBio() throws Exception {
        var before = account(11);
        for (String body :
                List.of(
                        "{}",
                        "{\"bio\":null}",
                        "{\"bio\":123}",
                        "{\"bio\":true}",
                        "{\"bio\":[]}",
                        "{\"bio\":\"合法简介\",\"userId\":22}",
                        "{\"bio\":\"合法简介\",\"email\":\"other@example.test\"}",
                        "{\"bio\":\"合法简介\",\"username\":\"changed\"}",
                        "{\"bio\":\"合法简介\",\"roles\":[\"ROLE_ADMIN\"]}")) {
            mvc.perform(
                            patch("/api/me/profile")
                                    .header("Authorization", "Bearer " + owner)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(account(11)).isEqualTo(before);
        verifyNoInteractions(profiles);
    }

    @Test
    void rejectsContactsAtServerAndDoesNotPersistRejectedText() throws Exception {
        var before = account(11);
        for (String value :
                List.of(
                        "微信：food123",
                        "Q Q：12345678",
                        "１３８－００１３－８０００",
                        "https://example.com",
                        "www.example.cn",
                        "wx\u200b:food123",
                        "192.168.1.2")) {
            update(value)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("USER_BIO_CONTACT_NOT_ALLOWED"));
        }
        assertThat(account(11)).isEqualTo(before);
        verify(profiles, never()).lock(anyLong());
    }

    @Test
    void rejectsUnavailableAccountsAndMissingAuthenticationBeforeProfileAccess() throws Exception {
        mvc.perform(
                        patch("/api/me/profile")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"bio\":\"新简介\"}"))
                .andExpect(status().isUnauthorized());
        for (String status : List.of("LOCKED", "DISABLED", "DELETED")) {
            jdbc.update("UPDATE users SET status=? WHERE id=11", status);
            update("新简介").andExpect(status().isUnauthorized());
            mvc.perform(get("/api/me/profile").header("Authorization", "Bearer " + owner))
                    .andExpect(status().isUnauthorized());
        }
        jdbc.update("UPDATE users SET status='ACTIVE',email_verified=FALSE WHERE id=11");
        update("新简介").andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }

    @Test
    void repeatsAccountStateCheckInsideWriteTransaction() throws Exception {
        doReturn(null).when(profiles).lock(11);
        update("新简介")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_PROFILE_UNAVAILABLE"));
        verify(profiles, never()).updateBio(anyLong(), anyString());
    }

    @Test
    void failuresAreNotFakeEmptyProfilesAndUpdateRollsBack() throws Exception {
        var before = account(11);
        doThrow(new DataAccessResourceFailureException("test-only")).when(profiles).find(11);
        mvc.perform(get("/api/me/profile").header("Authorization", "Bearer " + owner))
                .andExpect(status().isInternalServerError());
        reset(profiles);
        // 在同一事务内实际写入后注入异常，验证简介和行版本一起回滚。
        doAnswer(
                        invocation -> {
                            jdbc.update(
                                    "UPDATE users SET bio='故障注入',row_version=row_version+1 WHERE id=11");
                            throw new IllegalStateException("test-only");
                        })
                .when(profiles)
                .updateBio(eq(11L), anyString());
        update("新简介").andExpect(status().isInternalServerError());
        assertThat(account(11)).isEqualTo(before);
    }

    @Test
    void concurrentBioWritesDoNotLoseCredentialOrOtherColumns() throws Exception {
        var before = account(11);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> update("第一份简介").andExpect(status().isOk()));
            var two = pool.submit(() -> update("第二份简介").andExpect(status().isOk()));
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
        }
        var after = account(11);
        assertThat(after.get("bio")).isIn("第一份简介", "第二份简介");
        assertThat(((Number) after.get("row_version")).longValue()).isEqualTo(2);
        for (String field :
                List.of("email", "username", "password_hash", "credential_version", "status")) {
            assertThat(after.get(field)).isEqualTo(before.get(field));
        }
    }

    @Test
    void upgradeFromV25PreservesExistingAccountAndIsRepeatable() throws Exception {
        String name = "ginkgo_profile_upgrade_it_" + UUID.randomUUID().toString().replace("-", "");
        boolean made = false;
        try {
            try (var c = connect("");
                    var s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE DATABASE `"
                                + name
                                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                made = true;
            }
            Flyway.configure()
                    .dataSource(url(name), username(), password())
                    .locations("classpath:db/migration")
                    .target("25")
                    .load()
                    .migrate();
            var db =
                    new JdbcTemplate(
                            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                    url(name), username(), password()));
            db.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified,row_version,credential_version) VALUES(7,'legacy','legacy@example.test','legacy-private-hash',TRUE,9,4)");
            var before =
                    db.queryForMap(
                            "SELECT id,username,email,password_hash,row_version,credential_version,created_at,updated_at FROM users WHERE id=7");
            var flyway =
                    Flyway.configure()
                            .dataSource(url(name), username(), password())
                            .locations("classpath:db/migration")
                            .target("26")
                            .load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(
                            db.queryForMap(
                                    "SELECT id,username,email,password_hash,row_version,credential_version,created_at,updated_at FROM users WHERE id=7"))
                    .isEqualTo(before);
            assertThat(db.queryForObject("SELECT bio FROM users WHERE id=7", String.class))
                    .isEmpty();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {
            if (made) {
                drop(name);
            }
        }
    }

    private ResultActions update(String bio) throws Exception {
        return mvc.perform(
                patch("/api/me/profile")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("bio", bio))));
    }

    private Map<String, Object> account(long id) {
        return jdbc.queryForMap("SELECT * FROM users WHERE id=?", id);
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
        return System.getenv().getOrDefault("PROFILE_TEST_DB_USERNAME", "root");
    }

    private static String password() {
        return Objects.requireNonNull(
                System.getenv("PROFILE_TEST_DB_PASSWORD"), "Set isolated profile test password");
    }

    private static String url(String name) {
        String server =
                System.getenv()
                        .getOrDefault("PROFILE_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
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

    private static void drop(String name) throws SQLException {
        if (!name.matches("ginkgo_profile_(?:upgrade_)?it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test DB");
        }
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate("DROP DATABASE `" + name + "`");
        }
    }
}
