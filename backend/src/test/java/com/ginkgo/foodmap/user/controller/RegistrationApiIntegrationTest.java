package com.ginkgo.foodmap.user.controller;

import com.ginkgo.foodmap.user.domain.VerificationCodePurpose;
import com.ginkgo.foodmap.user.dto.VerificationCodeReservation;
import com.ginkgo.foodmap.user.service.VerificationCodeService;
import com.ginkgo.foodmap.security.service.LoginProtectionService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册、登录与密码重置的完整接口集成测试，使用真实 MySQL、Flyway、Mapper、事务、BCrypt 和 Redis Lua。
 *
 * <p>显式传入 {@code -Dauth.integration=true} 并通过 {@code AUTH_TEST_DB_PASSWORD} 提供本机
 * MySQL 密码后运行。每次运行创建随机命名的临时数据库，绝不连接业务数据库；Redis 使用4号库的
 * 独立随机前缀，仅精确清理测试自有键。验证码由真实服务预留并激活，邮件发送器被替换，避免发信。</p>
 */
@EnabledIfSystemProperty(named = "auth.integration", matches = "true")
@ActiveProfiles("test")
// 邮件发送器已被替换为 Mock，此处关闭仅面向真实 SMTP 连接的健康探针。
@SpringBootTest(properties = "management.health.mail.enabled=false")
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@org.springframework.context.annotation.Import(com.ginkgo.foodmap.security.TestJwtKeys.class)
class RegistrationApiIntegrationTest {

    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE = "ginkgo_auth_it_" + RUN_ID;
    private static final String MYSQL_SERVER = mysqlServer();
    private static final String REDIS_PREFIX = "test:auth:registration:{verification-code}:" + RUN_ID;
    private static boolean databaseCreated;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private VerificationCodeService verificationCodeService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired private ObjectMapper json;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private LoginProtectionService loginProtection;
    private final List<String> authKeys = new ArrayList<>();

    /** 显式阻止真实邮件发送，测试只验证注册及验证码消费能力。 */
    @MockitoBean
    private JavaMailSender mailSender;

    /** 创建测试专属空库，交给应用自己的 Flyway 脚本初始化，避免手工复制建表 SQL。 */
    @DynamicPropertySource
    static void configureInfrastructure(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL_SERVER, "root", databasePassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + DATABASE
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            databaseCreated = true;
        }
        registry.add("spring.datasource.url", () -> MYSQL_SERVER + DATABASE
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", RegistrationApiIntegrationTest::databasePassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 6379);
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.database", () -> 4);
        registry.add("app.user.verification-code.key-prefix", () -> REDIS_PREFIX);
        registry.add("app.security.authentication.jwt.time-to-live", () -> "5m");
    }

    /** 精确删除本次运行的 Redis 键，不使用 FLUSHDB，不涉及真实用户验证码。 */
    @AfterEach
    void cleanRedis() {
        Set<String> keys = redisTemplate.keys(REDIS_PREFIX + ":*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        if (!authKeys.isEmpty()) redisTemplate.delete(authKeys);
        verifyNoInteractions(mailSender);
    }

    /** 只允许移除本测试确实创建过的、符合严格随机名称规则的临时库。 */
    @AfterAll
    static void cleanDatabase() throws Exception {
        if (databaseCreated && DATABASE.matches("ginkgo_auth_it_[a-f0-9]{32}")) {
            try (var connection = DriverManager.getConnection(MYSQL_SERVER, "root", databasePassword());
                 var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE `" + DATABASE + "`");
            }
        }
    }

    /** 正常请求必须真实写入 BCrypt 哈希和普通用户角色，响应不返回密码或登录凭证。 */
    @Test
    void shouldRegisterAndAssignDefaultRole() throws Exception {
        String email = "success@std.uestc.edu.cn";
        postRegistration(email, "success_user", activeCode(email), "Ginkgo2026")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(passwordEncoder.matches("Ginkgo2026", hash)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_roles ur
                JOIN users u ON u.id = ur.user_id
                JOIN roles r ON r.id = ur.role_id
                WHERE u.email = ? AND r.code = 'ROLE_USER'
                """, Integer.class, email)).isEqualTo(1);
    }

    /** 错误验证码返回400且不创建账号；未达错误上限时，随后正确验证码仍可注册。 */
    @Test
    void shouldRejectWrongCodeWithoutCreatingUser() throws Exception {
        String email = "wrongcode@std.uestc.edu.cn";
        String code = activeCode(email);
        String wrongCode = code.equals("000000") ? "000001" : "000000";
        postRegistration(email, "wrongcode_user", wrongCode, "Ginkgo2026")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AUTH_CODE_INVALID_OR_EXPIRED"))
                .andExpect(jsonPath("$.message").value("验证码错误或已失效"));
        assertUserAbsent(email);

        postRegistration(email, "wrongcode_user", code, "Ginkgo2026")
                .andExpect(status().isCreated());
    }

    /** 已注册邮箱返回409，且不能覆盖原账号密码或增加第二条用户记录。 */
    @Test
    void shouldRejectDuplicateEmail() throws Exception {
        String email = "duplicateemail@std.uestc.edu.cn";
        postRegistration(email, "original_email_user", activeCode(email), "Ginkgo2026")
                .andExpect(status().isCreated());
        String hashBefore = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, email);

        postRegistration(email, "different_user", "123456", "OtherPassword2026")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.message").value("该邮箱已注册"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, email)).isEqualTo(hashBefore);
    }

    /** 重复用户名返回409，不能创建另一个账号，也不能提前消费第二位用户的有效验证码。 */
    @Test
    void shouldRejectDuplicateUsernameWithoutConsumingCode() throws Exception {
        String firstEmail = "firstusername@std.uestc.edu.cn";
        String secondEmail = "secondusername@std.uestc.edu.cn";
        postRegistration(firstEmail, "duplicate_name", activeCode(firstEmail), "Ginkgo2026")
                .andExpect(status().isCreated());
        String secondCode = activeCode(secondEmail);

        postRegistration(secondEmail, "duplicate_name", secondCode, "Ginkgo2026")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_USERNAME_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.message").value("该用户名已被使用"));
        assertUserAbsent(secondEmail);

        postRegistration(secondEmail, "available_name", secondCode, "Ginkgo2026")
                .andExpect(status().isCreated());
    }

    /** 非校园邮箱必须由服务端拒绝，即使请求直接绕过前端表单。 */
    @Test
    void shouldRejectNonCampusEmail() throws Exception {
        postRegistration("outsider@example.com", "outsider_user", "123456", "Ginkgo2026")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_CAMPUS_EMAIL_REQUIRED"));
        assertUserAbsent("outsider@example.com");
    }

    /** 弱密码必须被服务端拒绝，失败请求不能留下用户记录。 */
    @Test
    void shouldRejectWeakPassword() throws Exception {
        String email = "weakpassword@std.uestc.edu.cn";
        postRegistration(email, "weakpassword_user", "123456", "123")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_PASSWORD_WEAK"));
        assertUserAbsent(email);
    }

    /** 验证码格式错误时返回统一参数错误，响应只包含字段名和安全提示。 */
    @Test
    void shouldRejectMalformedVerificationCode() throws Exception {
        String email = "malformed@std.uestc.edu.cn";
        postRegistration(email, "malformed_user", "12ab", "Ginkgo2026")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data[0].field").value("verificationCode"));
        assertUserAbsent(email);
    }

    /** 使用真实 MySQL 与 Redis 完成注册、两次登录、读取身份和退出，验证两次会话互不影响。 */
    @Test
    void shouldRegisterLoginAndLogoutWithRealInfrastructure() throws Exception {
        String email = "loginflow@std.uestc.edu.cn";
        postRegistration(email, "login_flow_user", activeCode(email), "Ginkgo2026")
                .andExpect(status().isCreated());
        rememberLoginKeys("login_flow_user");
        String first = loginToken("LOGIN_FLOW_USER");
        String second = loginToken("login_flow_user");
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("login_flow_user"));
        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + first)).andExpect(status().isOk());
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + first)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + second)).andExpect(status().isOk());
        assertThat(redisTemplate.hasKey("auth:session:" + jwtDecoder.decode(first).getId())).isFalse();
        assertThat(redisTemplate.getExpire("auth:session:" + jwtDecoder.decode(second).getId())).isBetween(1L, 300L);
    }

    /** MySQL 大小写和重音不敏感的用户名变体共享同一个账号限流键，不能更换写法绕过锁定。 */
    @Test
    void shouldShareLoginLimitAcrossDatabaseUsernameAliases() throws Exception {
        String email = "loginlimit@std.uestc.edu.cn";
        postRegistration(email, "limited_login_user", activeCode(email), "Ginkgo2026")
                .andExpect(status().isCreated());
        rememberLoginKeys("limited_login_user");
        String[] aliases = {"limited_login_user", "LIMITED_LOGIN_USER", "límited_login_user"};
        for (int index = 0; index < 5; index++) {
            postLogin(aliases[index % aliases.length], "wrongPassword1")
                    .andExpect(index < 4 ? status().isUnauthorized() : status().isTooManyRequests());
        }
        postLogin("limited_login_user", "Ginkgo2026").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_LOCKED"));
    }

    /** 真实验证码只能改密一次，两个旧会话同时失效，新密码能重新登录。 */
    @Test
    void shouldResetPasswordOnceAndRevokeAllOldCredentials() throws Exception {
        String email = "resetflow@std.uestc.edu.cn";
        createResetAccount(email, "reset_flow_user");
        rememberLoginKeys("reset_flow_user");
        String first = loginToken("reset_flow_user");
        String second = loginToken("reset_flow_user");
        String code = activeCode(VerificationCodePurpose.PASSWORD_RESET, email);
        postPasswordReset(email, code, "NewPassword2026").andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(jdbcTemplate.queryForObject("SELECT credential_version FROM users WHERE email = ?",
                Long.class, email)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT row_version FROM users WHERE email = ?",
                Long.class, email)).isEqualTo(1L);
        for (String token : List.of(first, second)) {
            assertThat(redisTemplate.hasKey("auth:session:" + jwtDecoder.decode(token).getId())).isTrue();
            mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        postPasswordReset(email, code, "ReplayPassword2026").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_CODE_INVALID_OR_EXPIRED"));
        postLogin("reset_flow_user", "Ginkgo2026").andExpect(status().isUnauthorized());
        String fresh = loginToken("reset_flow_user", "NewPassword2026");
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + fresh)).andExpect(status().isOk());
        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(passwordEncoder.matches("NewPassword2026", hash)).isTrue();
        assertThat(passwordEncoder.matches("ReplayPassword2026", hash)).isFalse();
    }

    /** 注册用途的验证码不能用于改密；失败不能更新密码或凭证版本。 */
    @Test
    void shouldRejectRegistrationCodeForPasswordReset() throws Exception {
        String email = "resetpurpose@std.uestc.edu.cn";
        createResetAccount(email, "reset_purpose_user");
        String code = activeCode(email);
        postPasswordReset(email, code, "NewPassword2026").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_CODE_INVALID_OR_EXPIRED"));
        assertThat(jdbcTemplate.queryForObject("SELECT credential_version FROM users WHERE email = ?",
                Long.class, email)).isZero();
        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(passwordEncoder.matches("Ginkgo2026", hash)).isTrue();
    }

    /** MySQL 重音不敏感索引会命中相似邮箱，但另一个邮箱的验证码不能修改绑定账号。 */
    @Test
    void shouldRejectAccentedMailboxAliasForPasswordReset() throws Exception {
        String email = "resetalias@std.uestc.edu.cn";
        String alias = "résétalias@std.uestc.edu.cn";
        createResetAccount(email, "reset_alias_user");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?",
                Integer.class, alias)).isEqualTo(1);
        String code = activeCode(VerificationCodePurpose.PASSWORD_RESET, alias);
        postPasswordReset(alias, code, "NewPassword2026").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_CODE_INVALID_OR_EXPIRED"));
        assertThat(jdbcTemplate.queryForObject("SELECT credential_version FROM users WHERE email = ?",
                Long.class, email)).isZero();
        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(passwordEncoder.matches("Ginkgo2026", hash)).isTrue();
    }

    /** 两个并发请求共享同一真实验证码，必须只有一个成功且凭证版本只递增一次。 */
    @Test
    void shouldAllowOnlyOneConcurrentPasswordReset() throws Exception {
        String email = "resetconcurrent@std.uestc.edu.cn";
        createResetAccount(email, "reset_concurrent_user");
        String code = activeCode(VerificationCodePurpose.PASSWORD_RESET, email);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return postPasswordReset(email, code, "FirstPassword2026").andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                start.await();
                return postPasswordReset(email, code, "SecondPassword2026").andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 400);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT credential_version FROM users WHERE email = ?",
                Long.class, email)).isEqualTo(1L);
        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(passwordEncoder.matches("FirstPassword2026", hash)
                ^ passwordEncoder.matches("SecondPassword2026", hash)).isTrue();
    }

    /** 直接建立受控测试账号，避免注册发码冷却影响后续重置发码，不修改任何验证码限制。 */
    private void createResetAccount(String email, String username) {
        jdbcTemplate.update("""
                INSERT INTO users(username, email, password_hash, email_verified, email_verified_at)
                VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP(6))
                """, username, email, passwordEncoder.encode("Ginkgo2026"));
        jdbcTemplate.update("""
                INSERT INTO user_roles(user_id, role_id)
                SELECT u.id, r.id FROM users u CROSS JOIN roles r WHERE u.email = ? AND r.code = 'ROLE_USER'
                """, email);
    }

    private ResultActions postPasswordReset(String email, String code, String password) throws Exception {
        return mockMvc.perform(post("/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email,
                        "verificationCode", code, "newPassword", password))));
    }

    private void rememberLoginKeys(String username) {
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        String key = loginProtection.key(userId, username);
        authKeys.addAll(List.of(key + ":failures", key + ":lock"));
    }

    private String loginToken(String username) throws Exception {
        return loginToken(username, "Ginkgo2026");
    }

    private String loginToken(String username, String password) throws Exception {
        String body = postLogin(username, password).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).path("data").path("accessToken").asText();
        authKeys.add("auth:session:" + jwtDecoder.decode(token).getId());
        return token;
    }

    private ResultActions postLogin(String username, String password) throws Exception {
        return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("username", username, "password", password))));
    }

    /** 通过真实 Redis 流程生成测试验证码，不通过 SMTP 发送。 */
    private String activeCode(String email) {
        return activeCode(VerificationCodePurpose.REGISTER, email);
    }

    private String activeCode(VerificationCodePurpose purpose, String email) {
        VerificationCodeReservation reservation = verificationCodeService.reserve(purpose, email);
        verificationCodeService.activate(reservation);
        return reservation.getCode();
    }

    private void assertUserAbsent(String email) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
    }

    /** 所有入参均为本文件内受控的测试常量，不接收外部输入，也不打印敏感请求内容。 */
    private ResultActions postRegistration(String email, String username, String code, String password)
            throws Exception {
        return mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","username":"%s","verificationCode":"%s","password":"%s"}
                        """.formatted(email, username, code, password)));
    }

    private static String databasePassword() {
        String password = System.getenv("AUTH_TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("AUTH_TEST_DB_PASSWORD is required for MySQL integration tests");
        }
        return password;
    }

    /** 完整联调可使用独立回环MySQL实例；不允许URL携带库名、凭据或任意远程参数。 */
    private static String mysqlServer() {
        String server = System.getenv().getOrDefault("AUTH_TEST_DB_SERVER", "jdbc:mysql://127.0.0.1:3306/");
        if (!server.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[1-9][0-9]{0,4}/")) {
            throw new IllegalStateException("AUTH_TEST_DB_SERVER must be an explicit loopback MySQL server URL");
        }
        return server;
    }
}
