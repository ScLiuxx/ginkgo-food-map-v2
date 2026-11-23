package com.foodmap.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.foodmap.security.TestJwtKeys;
import com.foodmap.security.config.AuthenticationProperties;
import com.foodmap.security.service.LoginSessionService;
import com.foodmap.user.mapper.UserProfileMapper;
import com.foodmap.user.service.UserAccountService;

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
import org.springframework.security.crypto.password.PasswordEncoder;
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
            "app.file.oss.bucket=test-avatar-bucket",
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
    @Autowired UserAccountService accounts;
    @Autowired PasswordEncoder passwordEncoder;
    @MockitoBean LoginSessionService sessions;
    @MockitoSpyBean UserProfileMapper profiles;
    @MockitoBean com.foodmap.file.storage.ImageStorageClient storage;
    private String owner;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "foodmap_profile_it_" + UUID.randomUUID().toString().replace("-", "");
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
        reset(sessions, profiles, storage);
        jdbc.update("DELETE FROM content_likes");
        jdbc.update("DELETE FROM growth_rewards");
        jdbc.update("DELETE FROM growth_accounts");
        jdbc.update("DELETE FROM review_photos");
        jdbc.update("DELETE FROM review_replies");
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM restaurant_review_statistics");
        jdbc.update("DELETE FROM restaurants");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM media_files");
        jdbc.update("DELETE FROM users");
        for (long id : List.of(11L, 22L)) {
            jdbc.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified,bio)"
                            + " VALUES(?,?,?,?,TRUE,?)",
                    id,
                    "profile_" + id,
                    "private_local" + id + "@std.uestc.edu.cn",
                    "test-only-private-hash",
                    id == 11 ? "原有简介" : "其他账号的简介");
            jdbc.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE"
                            + " code='ROLE_USER'",
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
                "INSERT INTO restaurants(id,name,category_code)"
                        + " VALUES(71,'主页店','OTHER'),(72,'隐藏店','OTHER')");
        jdbc.update(
                "UPDATE restaurants SET"
                    + " deleted_at=CURRENT_TIMESTAMP(6),deleted_by=11,delete_reason_code='ADMIN_REMOVAL'"
                    + " WHERE id=72");
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
                "INSERT INTO"
                    + " reviews(id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,recommendation,body,client_request_id,request_hash,created_at)"
                    + " VALUES(?,?,?,8,8,8,8,'RECOMMENDED','主页公开点评正文',UUID_TO_BIN(?),UNHEX(SHA2('profile',256)),?)",
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
    void renamesOnlyTheCurrentAccountAndKeepsItsSessionAndPassword() throws Exception {
        String password = "Foodmap_test_2026";
        jdbc.update("UPDATE users SET password_hash=? WHERE id=11", passwordEncoder.encode(password));
        var before = account(11);
        var other = account(22);

        rename(owner, " 中 ")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.userId").value("11"))
                .andExpect(jsonPath("$.data.username").value("中"))
                .andExpect(jsonPath("$.data.bio").value("原有简介"));

        var after = account(11);
        assertThat(after.get("username")).isEqualTo("中");
        assertThat(((Number) after.get("row_version")).longValue()).isEqualTo(1);
        before.forEach((field, value) -> {
            if (!Set.of("username", "row_version", "updated_at").contains(field)) {
                assertThat(after.get(field)).as(field).isEqualTo(value);
            }
        });
        assertThat(account(22)).isEqualTo(other);
        rename(owner, "中").andExpect(status().isOk());
        assertThat(account(11)).isEqualTo(after);

        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("中"));
        mvc.perform(get("/api/users/11/profile").header("Authorization", "Bearer " + token(22)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("中"));
        assertThat(accounts.authenticate("中", password)).isPresent();
        assertThat(accounts.authenticate("profile_11", password)).isEmpty();
        assertThat(accounts.authenticate("private_local11@std.uestc.edu.cn", password)).isPresent();
    }

    @Test
    void rejectsInvalidOrOccupiedUsernamesWithoutChangingEitherAccount() throws Exception {
        var before = account(11);
        var other = account(22);
        mvc.perform(patch("/api/me/profile/username")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"中\"}"))
                .andExpect(status().isUnauthorized());
        for (String body : List.of("{}", "{\"username\":42}", "{\"username\":\"中\",\"userId\":22}")) {
            mvc.perform(patch("/api/me/profile/username")
                            .header("Authorization", "Bearer " + owner)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        for (String name : List.of(" 中 文 ", "中".repeat(25))) {
            rename(owner, name)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("USER_USERNAME_INVALID"));
        }
        rename(owner, "profile_22")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_USERNAME_ALREADY_REGISTERED"));
        assertThat(account(11)).isEqualTo(before);
        assertThat(account(22)).isEqualTo(other);
        rename(owner, "PROFILE_22").andExpect(status().isOk());
    }

    @Test
    void concurrentAccountsCannotClaimTheSameUsername() throws Exception {
        String secondToken = token(22);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> rename(owner, "同").andReturn().getResponse().getStatus());
            var second =
                    pool.submit(() -> rename(secondToken, "同").andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username='同'", Integer.class))
                .isEqualTo(1);
    }

    private ResultActions rename(String token, String username) throws Exception {
        return mvc.perform(patch("/api/me/profile/username?userId=22")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username))));
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
                                    "UPDATE users SET bio='故障注入',row_version=row_version+1 WHERE"
                                            + " id=11");
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
        String name = "foodmap_profile_upgrade_it_" + UUID.randomUUID().toString().replace("-", "");
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
                    "INSERT INTO"
                        + " users(id,username,email,password_hash,email_verified,row_version,credential_version)"
                        + " VALUES(7,'legacy','legacy@example.test','legacy-private-hash',TRUE,9,4)");
            var before =
                    db.queryForMap(
                            "SELECT"
                                + " id,username,email,password_hash,row_version,credential_version,created_at,updated_at"
                                + " FROM users WHERE id=7");
            var flyway =
                    Flyway.configure()
                            .dataSource(url(name), username(), password())
                            .locations("classpath:db/migration")
                            .target("26")
                            .load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(
                            db.queryForMap(
                                    "SELECT"
                                        + " id,username,email,password_hash,row_version,credential_version,created_at,updated_at"
                                        + " FROM users WHERE id=7"))
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

    /** 从有账号和既有文件的V47升级，原业务列不变；重复运行不重复增加字段或约束。 */
    @Test
    void avatarMigrationPreservesV47DataAndIsRepeatable() throws Exception {
        String name = "foodmap_profile_upgrade_it_" + UUID.randomUUID().toString().replace("-", "");
        boolean made = false;
        try {
            try (var connection = connect("");
                    var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE DATABASE `"
                                + name
                                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                made = true;
            }
            Flyway.configure()
                    .dataSource(url(name), username(), password())
                    .locations("classpath:db/migration")
                    .target("47")
                    .load()
                    .migrate();
            var db =
                    new JdbcTemplate(
                            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                    url(name), username(), password()));
            db.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified,bio)"
                            + " VALUES(7,'legacy','legacy@example.test','test-only',TRUE,'保留简介')");
            db.update(
                    "INSERT INTO"
                        + " media_files(id,uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,expires_at,purpose)"
                        + " VALUES(8,7,'test-bucket','review-images/test.jpg','image/jpeg',100,80,60,UNHEX(SHA2('test',256)),'TEMPORARY',TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP(6)),'REVIEW')");
            var userBefore = db.queryForMap("SELECT * FROM users WHERE id=7");
            var fileBefore = db.queryForMap("SELECT * FROM media_files WHERE id=8");
            var flyway =
                    Flyway.configure()
                            .dataSource(url(name), username(), password())
                            .locations("classpath:db/migration")
                            .target("48")
                            .load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            var after = db.queryForMap("SELECT * FROM users WHERE id=7");
            assertThat(after.remove("avatar_file_id")).isNull();
            assertThat(after).isEqualTo(userBefore);
            assertThat(db.queryForMap("SELECT * FROM media_files WHERE id=8"))
                    .usingRecursiveComparison()
                    .isEqualTo(fileBefore);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {
            if (made) {
                drop(name);
            }
        }
    }

    @Test
    void avatarReplacementRejectsBadNewFileWithoutReleasingCurrentImage() throws Exception {
        avatarFile(501, 11, "AVATAR", "TEMPORARY");
        avatar("501").andExpect(status().isNoContent());
        avatarFile(502, 22, "AVATAR", "TEMPORARY");
        var before = account(11);
        avatar("502").andExpect(status().isConflict());
        assertThat(account(11)).isEqualTo(before);
        assertThat(mediaStatus(501)).isEqualTo("BOUND");
        for (String assignment :
                List.of(
                        "purpose='REVIEW'",
                        "uploader_user_id=22",
                        "status='PENDING_DELETE',expires_at=CURRENT_TIMESTAMP(6)")) {
            jdbc.update("UPDATE media_files SET " + assignment + " WHERE id=501");
            mvc.perform(get("/api/users/11/avatar")).andExpect(status().isNotFound());
            jdbc.update(
                    "UPDATE media_files SET"
                            + " purpose='AVATAR',uploader_user_id=11,status='BOUND',expires_at=NULL"
                            + " WHERE id=501");
        }
        verifyNoInteractions(storage);
    }

    /** 上传真实JPEG，经内容检查后绑定；公开只读当前关联，私有对象键和文件ID不出现在读取协议中。 */
    @Test
    void avatarUploadSaveReadReplaceAndReplay() throws Exception {
        mvc.perform(get("/api/users/11/avatar")).andExpect(status().isNoContent());
        var image =
                new java.awt.image.BufferedImage(
                        128, 128, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpg", bytes);
        var upload =
                mvc.perform(
                                multipart("/api/files/images")
                                        .file(
                                                new org.springframework.mock.web.MockMultipartFile(
                                                        "file",
                                                        "avatar.jpg",
                                                        "image/jpeg",
                                                        bytes.toByteArray()))
                                        .param("purpose", "AVATAR")
                                        .header("Authorization", "Bearer " + owner))
                        .andExpect(status().isCreated())
                        .andReturn();
        String first =
                json.readTree(upload.getResponse().getContentAsString())
                        .get("data")
                        .get("fileId")
                        .asText();
        var uploaded = jdbc.queryForMap("SELECT * FROM media_files WHERE id=?", first);
        assertThat(uploaded.get("storage_key").toString()).startsWith("avatars/");
        assertThat(uploaded.get("purpose")).isEqualTo("AVATAR");
        var before = account(11);
        avatar(first).andExpect(status().isNoContent());
        var saved = account(11);
        avatar(first).andExpect(status().isNoContent());
        assertThat(account(11)).isEqualTo(saved);
        assertThat(saved.get("bio")).isEqualTo(before.get("bio"));
        assertThat(saved.get("credential_version")).isEqualTo(before.get("credential_version"));
        when(storage.read(anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return bytes.toByteArray();
                        });
        mvc.perform(get("/api/users/11/avatar"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(bytes.toByteArray()))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(delete("/api/files/images/" + first).header("Authorization", "Bearer " + owner))
                .andExpect(status().isConflict());
        avatarFile(501, 11, "AVATAR", "TEMPORARY");
        avatar("501").andExpect(status().isNoContent());
        assertThat(mediaStatus(Long.parseLong(first))).isEqualTo("PENDING_DELETE");
        assertThat(mediaStatus(501)).isEqualTo("BOUND");
        // 旧文件重放不覆盖新头像；只有当前绑定文件才允许幂等重试。
        avatar(first).andExpect(status().isConflict());
        assertThat(account(11).get("avatar_file_id")).isEqualTo(501L);
    }

    @Test
    void avatarRejectsInvalidForeignWrongPurposeExpiredAndUnboundMedia() throws Exception {
        mvc.perform(
                        put("/api/me/avatar")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"fileId\":\"501\"}"))
                .andExpect(status().isUnauthorized());
        for (String invalid : List.of("0", "01", "-1", "abc", "9223372036854775808")) {
            avatar(invalid).andExpect(status().isBadRequest());
            mvc.perform(get("/api/users/" + invalid + "/avatar"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        put("/api/me/avatar")
                                .header("Authorization", "Bearer " + owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
        avatarFile(501, 22, "AVATAR", "TEMPORARY");
        avatarFile(502, 11, "REVIEW", "TEMPORARY");
        avatarFile(503, 11, "RESTAURANT", "TEMPORARY");
        avatarFile(505, 11, "AVATAR", "TEMPORARY");
        jdbc.update(
                "UPDATE media_files SET expires_at=TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP(6)) WHERE"
                        + " id=505");
        avatarFile(506, 11, "AVATAR", "BOUND");
        for (String invalid : List.of("501", "502", "503", "505", "506", "999")) {
            avatar(invalid).andExpect(status().isConflict());
        }
        assertThat(account(11).get("avatar_file_id")).isNull();
        verifyNoInteractions(storage);
    }

    @Test
    void avatarUpdateRollbackRestoresBothFileStatesAndUserAssociation() throws Exception {
        avatarFile(501, 11, "AVATAR", "TEMPORARY");
        avatarFile(502, 11, "AVATAR", "TEMPORARY");
        avatar("501").andExpect(status().isNoContent());
        var before = account(11);
        doThrow(new DataAccessResourceFailureException("test-only failure"))
                .when(profiles)
                .updateAvatar(11, 502);
        avatar("502").andExpect(status().is5xxServerError());
        assertThat(account(11)).isEqualTo(before);
        assertThat(mediaStatus(501)).isEqualTo("BOUND");
        assertThat(mediaStatus(502)).isEqualTo("TEMPORARY");
        verifyNoInteractions(storage);
    }

    @Test
    void concurrentAvatarSavesLeaveOneCurrentBoundFile() throws Exception {
        avatarFile(501, 11, "AVATAR", "TEMPORARY");
        avatarFile(502, 11, "AVATAR", "TEMPORARY");
        var executor = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            var requests = new ArrayList<Future<Integer>>();
            for (String id : List.of("501", "502")) {
                requests.add(
                        executor.submit(
                                () -> {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    return avatar(id).andReturn().getResponse().getStatus();
                                }));
            }
            for (var request : requests) {
                assertThat(request.get(20, TimeUnit.SECONDS)).isEqualTo(204);
            }
        } finally {
            executor.shutdownNow();
        }
        long current = ((Number) account(11).get("avatar_file_id")).longValue();
        assertThat(mediaStatus(current)).isEqualTo("BOUND");
        assertThat(mediaStatus(current == 501 ? 502 : 501)).isEqualTo("PENDING_DELETE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM media_files WHERE status='BOUND'",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    void avatarVisibilityAndStorageFailureDoNotExposeOrLoseAssociation() throws Exception {
        mvc.perform(get("/api/users/999/avatar")).andExpect(status().isNotFound());
        avatarFile(501, 11, "AVATAR", "TEMPORARY");
        avatar("501").andExpect(status().isNoContent());
        when(storage.read(anyString(), anyString()))
                .thenThrow(
                        new com.foodmap.common.exception.BusinessException(
                                com.foodmap.file.exception.FileErrorCode.UNAVAILABLE));
        mvc.perform(get("/api/users/11/avatar")).andExpect(status().isServiceUnavailable());
        assertThat(account(11).get("avatar_file_id")).isEqualTo(501L);
        jdbc.update("UPDATE users SET status='DELETED' WHERE id=11");
        clearInvocations(storage);
        mvc.perform(get("/api/users/11/avatar")).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
        jdbc.update("UPDATE users SET status='ACTIVE',email_verified=FALSE WHERE id=11");
        mvc.perform(get("/api/users/11/avatar")).andExpect(status().isNotFound());
    }

    @Test
    void avatarUploadRejectsNonSquareOrOversizedImagesBeforeCloudWrite() throws Exception {
        for (int[] size : List.of(new int[] {256, 128}, new int[] {513, 513})) {
            var image =
                    new java.awt.image.BufferedImage(
                            size[0], size[1], java.awt.image.BufferedImage.TYPE_INT_RGB);
            var bytes = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "jpg", bytes);
            mvc.perform(
                            multipart("/api/files/images")
                                    .file(
                                            new org.springframework.mock.web.MockMultipartFile(
                                                    "file",
                                                    "image.jpg",
                                                    "image/jpeg",
                                                    bytes.toByteArray()))
                                    .param("purpose", "AVATAR")
                                    .header("Authorization", "Bearer " + owner))
                    .andExpect(status().isBadRequest());
        }
        verify(storage, never()).put(anyString(), anyString(), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_files", Long.class)).isZero();
    }

    /** 测试文件只登记在随机隔离库，OSS完全模拟；状态组合遵循真实数据库约束。 */
    private void avatarFile(long id, long uploader, String purpose, String state) {
        jdbc.update(
                "INSERT INTO"
                    + " media_files(id,uploader_user_id,bucket_name,storage_key,content_type,byte_size,width,height,sha256,status,expires_at,bound_at,purpose)"
                    + " VALUES(?,?,'test-avatar-bucket',?,'image/jpeg',100,128,128,UNHEX(SHA2('test-avatar',256)),?,"
                    + "IF(?='BOUND',NULL,TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP(6))),IF(?='BOUND',CURRENT_TIMESTAMP(6),NULL),?)",
                id,
                uploader,
                "avatars/test-" + id + ".jpg",
                state,
                state,
                state,
                purpose);
    }

    private String mediaStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM media_files WHERE id=?", String.class, id);
    }

    private ResultActions avatar(String fileId) throws Exception {
        return mvc.perform(
                put("/api/me/avatar")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fileId", fileId))));
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
        if (!name.matches("foodmap_profile_(?:upgrade_)?it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test DB");
        }
        try (var c = connect("");
                var s = c.createStatement()) {
            s.executeUpdate("DROP DATABASE `" + name + "`");
        }
    }
}
