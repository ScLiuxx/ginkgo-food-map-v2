package com.ginkgo.foodmap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginProtectionService;
import com.ginkgo.foodmap.security.service.LoginSessionService;
import com.ginkgo.foodmap.user.domain.VerificationCodePurpose;
import com.ginkgo.foodmap.user.entity.UserEntity;
import com.ginkgo.foodmap.user.mapper.UserMapper;
import com.ginkgo.foodmap.user.service.VerificationCodeService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 真实 Mapper、BCrypt、RSA 签发解码和安全过滤链集成测试；Redis 独立进行真实原子性验证。 */
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:login_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:sql/user-persistence-schema.sql"
        })
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import({TestJwtKeys.class, LoginApiIntegrationTest.AdminProbe.class})
class LoginApiIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserMapper users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JwtEncoder encoder;
    @Autowired private AuthenticationProperties properties;
    @MockitoBean private LoginSessionService sessions;
    @MockitoBean private LoginProtectionService protection;

    /** Redis 验证码原子性另由真实 Redis 集成测试验证，此处验证改密与真实认证链的联动。 */
    @MockitoBean private VerificationCodeService codes;

    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();
    private UserEntity user;

    /** 注册自动登录复用真实BCrypt/JWT与安全链，最短用户名和纯数字4位密码可用。 */
    @Test
    void shouldRegisterAndEstablishUsableSession() throws Exception {
        String response =
                mvc.perform(
                                post("/auth/register-and-login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                {"email":"new@std.uestc.edu.cn","verificationCode":"123456","username":"Ka","password":"1234"}
                                """))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.user.username").value("Ka"))
                        .andExpect(jsonPath("$.data.user.roles[0]").value("ROLE_USER"))
                        .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String token = json.readTree(response).path("data").path("accessToken").asText();
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("Ka"));
        verify(codes).consume(VerificationCodePurpose.REGISTER, "new@std.uestc.edu.cn", "123456");
        assertThat(
                        passwords.matches(
                                "1234",
                                users.selectByUsername("Ka").orElseThrow().getPasswordHash()))
                .isTrue();
    }

    @BeforeEach
    void prepareAccount() {
        activeSessions.clear();
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM roles");
        jdbc.update(
                "INSERT INTO roles(id, code, name) VALUES (1, 'ROLE_USER', '普通用户'), (2, 'ROLE_ADMIN', '管理员')");
        user =
                UserEntity.registerVerified(
                        "ginkgo_user", "student@std.uestc.edu.cn", passwords.encode("Ginkgo2026"));
        users.insert(user);
        jdbc.update("INSERT INTO user_roles(user_id, role_id) VALUES (?, 1)", user.getId());
        when(protection.key(any(), anyString())).thenReturn("login-test");
        doAnswer(
                        call -> {
                            activeSessions.put(
                                    call.getArgument(0),
                                    call.getArgument(1) + ":" + call.getArgument(2));
                            return null;
                        })
                .when(sessions)
                .create(anyString(), anyLong(), anyLong(), any());
        when(sessions.isActive(anyString(), anyLong(), anyLong()))
                .thenAnswer(
                        call ->
                                (call.getArgument(1) + ":" + call.getArgument(2))
                                        .equals(activeSessions.get(call.getArgument(0))));
        doAnswer(
                        call -> {
                            activeSessions.remove(call.getArgument(0));
                            return null;
                        })
                .when(sessions)
                .revoke(anyString());
    }

    /** 注册即使夹带客户端角色字段也只授予普通用户；两种公开注册入口均不能生成管理员。 */
    @Test
    void shouldNeverGrantAdministratorThroughPublicRegistration() throws Exception {
        int index = 0;
        for (String path : List.of("/auth/register", "/auth/register-and-login")) {
            String username = "role_probe_" + index;
            String request =
                    """
                    {"email":"role-probe-%d@std.uestc.edu.cn","verificationCode":"123456",
                    "username":"%s","password":"1234","role":"ROLE_ADMIN",
                    "roles":["ROLE_ADMIN"],"isAdmin":true}
                    """
                            .formatted(index++, username);
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().is2xxSuccessful());
            var roles =
                    jdbc.queryForList(
                            "SELECT r.code FROM roles r JOIN user_roles ur ON ur.role_id=r.id "
                                    + "JOIN users u ON u.id=ur.user_id WHERE u.username=?",
                            String.class,
                            username);
            assertThat(roles).containsExactly("ROLE_USER");
        }
    }

    /** 统一登录返回真实角色；管理会话入口拒绝普通用户，撤销角色后原JWT也立即失去管理权限。 */
    @Test
    void shouldAuthorizeAdministratorSessionUsingCurrentDatabaseRoles() throws Exception {
        mvc.perform(get("/api/admin/me")).andExpect(status().isUnauthorized());
        String userToken = login();
        mvc.perform(get("/api/admin/me").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        jdbc.update("INSERT INTO user_roles(user_id, role_id) VALUES (?, 2)", user.getId());
        String response =
                loginRequest()
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath(
                                        "$.data.user.roles",
                                        org.hamcrest.Matchers.hasItem("ROLE_ADMIN")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String adminToken = json.readTree(response).path("data").path("accessToken").asText();
        mvc.perform(get("/api/admin/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.username").value("ginkgo_user"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        jdbc.update("DELETE FROM user_roles WHERE user_id = ? AND role_id = 2", user.getId());
        mvc.perform(get("/api/admin/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
        jdbc.update("INSERT INTO user_roles(user_id, role_id) VALUES (?, 2)", user.getId());
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
    }

    /** 登录返回可被真实过滤链接受的 JWT、公开用户信息及禁止缓存标头，不建立 HttpSession。 */
    @Test
    void shouldLoginAndReadCurrentUser() throws Exception {
        String token = login();
        var result =
                mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.username").value("ginkgo_user"))
                        .andExpect(jsonPath("$.data.roles[0]").value("ROLE_USER"))
                        .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                        .andExpect(jsonPath("$.data.credentialVersion").doesNotExist())
                        .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        verify(protection).succeeded("login-test");
    }

    /** 邮箱大小写及首尾空格规范化后登录同一账号，两种入口共用用户ID限流。 */
    @Test
    void shouldLoginWithRegisteredEmailAndShareAccountProtection() throws Exception {
        for (String account : List.of("ginkgo_user", " STUDENT@STD.UESTC.EDU.CN ")) {
            String response =
                    mvc.perform(
                                    post("/auth/login")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(json.writeValueAsString(
                                                    Map.of("username", account, "password", "Ginkgo2026"))))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.user.username").value("ginkgo_user"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            String token = json.readTree(response).path("data").path("accessToken").asText();
            mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(user.getId()));
        }
        verify(protection).key(user.getId(), "ginkgo_user");
        verify(protection).key(user.getId(), "STUDENT@STD.UESTC.EDU.CN");
    }

    /** 邮箱入口与用户名入口一样拒绝错误密码、未知邮箱、停用及未验证账号。 */
    @Test
    void shouldRejectInvalidEmailCredentialsAndUnavailableAccounts() throws Exception {
        for (String account : List.of("student@std.uestc.edu.cn", "missing@std.uestc.edu.cn")) {
            mvc.perform(
                            post("/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsString(
                                            Map.of("username", account, "password", "wrong"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        }
        for (String update : List.of("status = 'DISABLED'", "status = 'ACTIVE', email_verified = FALSE")) {
            jdbc.update("UPDATE users SET " + update + " WHERE id = ?", user.getId());
            mvc.perform(
                            post("/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"username\":\"student@std.uestc.edu.cn\",\"password\":\"Ginkgo2026\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        }
        verify(sessions, never()).create(anyString(), anyLong(), anyLong(), any());
    }

    /** 不存在账号、错误密码及停用账号返回同一提示，均不能签发会话。 */
    @Test
    void shouldHideAccountExistenceAndRejectUnavailableAccounts() throws Exception {
        for (String username : List.of("ginkgo_user", "missing_user")) {
            mvc.perform(
                            post("/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"username\":\""
                                                    + username
                                                    + "\",\"password\":\"wrongPassword1\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        }
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", user.getId());
        loginRequest()
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        verify(sessions, never()).create(anyString(), anyLong(), anyLong(), any());
    }

    /** 明确公开 POST 登录，字段错误返回400；GET 登录、匿名读取及 Basic 均不能绕过 Bearer 认证。 */
    @Test
    void shouldEnforceProtocolAndAuthenticationBoundaries() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
        mvc.perform(get("/auth/login")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        mvc.perform(post("/auth/logout")).andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/auth/me")
                                .header("Authorization", "Basic Z2lua2dvX3VzZXI6R2lua2dvMjAyNg=="))
                .andExpect(status().isUnauthorized());
    }

    /** 多次登录产生独立会话，退出后旧令牌立刻失效，另一个会话继续可用。 */
    @Test
    void shouldRevokeOnlyCurrentSession() throws Exception {
        String first = login();
        String second = login();
        assertThat(first).isNotEqualTo(second);
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + first))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + second))
                .andExpect(status().isOk());
    }

    /** 签名有效也不能绕过停用账号或已递增的凭证版本。 */
    @Test
    void shouldRejectDisabledAndPasswordChangedAccounts() throws Exception {
        String token = login();
        jdbc.update("UPDATE users SET credential_version = 1 WHERE id = ?", user.getId());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        jdbc.update(
                "UPDATE users SET credential_version = 0, status = 'LOCKED' WHERE id = ?",
                user.getId());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /** 每次读取当前角色；没有管理权限返回统一403，不能依靠客户端声明越权。 */
    @Test
    void shouldUseCurrentRolesForAuthorization() throws Exception {
        String token = login();
        mvc.perform(get("/api/test/admin").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        jdbc.update("INSERT INTO user_roles(user_id, role_id) VALUES (?, 2)", user.getId());
        mvc.perform(get("/api/test/admin").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM user_roles WHERE user_id = ? AND role_id = 2", user.getId());
        mvc.perform(get("/api/test/admin").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /** 即使本机签名正确，过期、错误发行方/受众或缺少必要声明也必须拒绝。 */
    @Test
    void shouldValidateSignedClaimsAndRejectTampering() throws Exception {
        for (int variant = 0; variant < 5; variant++) {
            String id = UUID.randomUUID().toString();
            activeSessions.put(id, user.getId() + ":0");
            var claims =
                    JwtClaimsSet.builder()
                            .subject(user.getId().toString())
                            .id(id)
                            .issuer(
                                    variant == 1
                                            ? "foreign-issuer"
                                            : properties.getJwt().getIssuer())
                            .audience(
                                    List.of(
                                            variant == 2
                                                    ? "foreign-api"
                                                    : properties.getJwt().getAudience()))
                            .issuedAt(Instant.now().minusSeconds(120));
            if (variant != 3) {
                claims.expiresAt(Instant.now().plusSeconds(variant == 0 ? -60 : 600));
            }
            if (variant != 4) {
                claims.claim("credential_version", 0L);
            }
            String token =
                    encoder.encode(
                                    JwtEncoderParameters.from(
                                            JwsHeader.with(SignatureAlgorithm.RS256).build(),
                                            claims.build()))
                            .getTokenValue();
            mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        String token = login();
        String tampered = (token.charAt(0) == 'a' ? "b" : "a") + token.substring(1);
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
        String[] pieces = token.split("\\.");
        String forged =
                pieces[0]
                        + "."
                        + pieces[1]
                        + "."
                        + (pieces[2].charAt(0) == 'a' ? "b" : "a")
                        + pieces[2].substring(1);
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    /** 锁定返回429，限流和会话基础设施故障返回503，绝不返回登录凭证。 */
    @Test
    void shouldFailClosedWhenLoginInfrastructureIsUnavailable() throws Exception {
        doThrow(new RedisConnectionFailureException("test only"))
                .when(protection)
                .check("login-test");
        loginRequest()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_SERVICE_UNAVAILABLE"));
        doNothing().when(protection).check("login-test");
        doThrow(new RedisConnectionFailureException("test only"))
                .when(sessions)
                .create(anyString(), anyLong(), anyLong(), any());
        loginRequest()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
    }

    /** 阈值失败和已锁定请求均使用429，不把锁定误认为密码校验成功。 */
    @Test
    void shouldReturnRateLimitForLockedAccount() throws Exception {
        when(protection.failed("login-test")).thenReturn(true);
        mvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"ginkgo_user\",\"password\":\"wrongPassword1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_LOCKED"));
        doThrow(
                        new com.ginkgo.foodmap.common.exception.BusinessException(
                                com.ginkgo.foodmap.security.exception.AuthenticationErrorCode
                                        .LOGIN_LOCKED))
                .when(protection)
                .check("login-test");
        loginRequest().andExpect(status().isTooManyRequests());
    }

    /** 会话存储读取故障时不可放行；退出写入失败时不得宣告服务端退出成功。 */
    @Test
    void shouldRejectSessionReadFailureAndReportLogoutFailure() throws Exception {
        String token = login();
        doThrow(new RedisConnectionFailureException("test only"))
                .when(sessions)
                .revoke(anyString());
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable());
        when(sessions.isActive(anyString(), anyLong(), anyLong()))
                .thenThrow(new RedisConnectionFailureException("test only"));
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_SERVICE_UNAVAILABLE"));
    }

    /** 重置密码原子递增版本，两个设备的旧 JWT 同时失效，只有保留原样的新密码能够登录。 */
    @Test
    void shouldResetPasswordAndInvalidateAllPreviousLogins() throws Exception {
        String first = login();
        String second = login();
        mvc.perform(
                        post("/auth/password-reset")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"email":"Student@STD.UESTC.EDU.CN","verificationCode":"012345",
                                 "newPassword":"NewPassword2026"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("ginkgo_user"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
        verify(codes)
                .consume(
                        VerificationCodePurpose.PASSWORD_RESET,
                        "student@std.uestc.edu.cn",
                        "012345");
        UserEntity changed = users.selectById(user.getId());
        assertThat(changed.getCredentialVersion()).isEqualTo(1L);
        assertThat(changed.getRowVersion()).isEqualTo(1L);
        assertThat(passwords.matches("NewPassword2026", changed.getPasswordHash())).isTrue();
        assertThat(passwords.matches("Ginkgo2026", changed.getPasswordHash())).isFalse();
        // 旧会话记录仍在存储中，证明拒绝来自最新凭证版本，而非测试提前删除了会话。
        assertThat(activeSessions).hasSize(2);
        for (String token : List.of(first, second)) {
            mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        }
        loginRequest().andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"ginkgo_user\",\"password\":\" NewPassword2026 \"}"))
                .andExpect(status().isUnauthorized());
        String response =
                mvc.perform(
                                post("/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\"ginkgo_user\",\"password\":\"NewPassword2026\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String freshToken = json.readTree(response).path("data").path("accessToken").asText();
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isOk());
    }

    /** 只公开指定 POST，缺失字段、错误验证码形状或无效 Bearer 都不能执行重置。 */
    @Test
    void shouldValidatePasswordResetProtocolAndAuthentication() throws Exception {
        mvc.perform(get("/auth/password-reset")).andExpect(status().isUnauthorized());
        for (String payload :
                List.of(
                        "{}",
                        """
                {"email":"student@std.uestc.edu.cn","verificationCode":"12ab","newPassword":"NewPassword2026"}
                """,
                        """
                {"email":"invalid","verificationCode":"012345","newPassword":"NewPassword2026"}
                """,
                        """
                {"email":"student@std.uestc.edu.cn","verificationCode":"012345","newPassword":" "}
                """)) {
            mvc.perform(
                            post("/auth/password-reset")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
        }
        mvc.perform(
                        post("/auth/password-reset")
                                .header("Authorization", "Bearer invalid")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(codes);
        assertThat(users.selectById(user.getId()).getCredentialVersion()).isZero();
    }

    /** 验证码服务拒绝或故障时，HTTP 保留业务错误码，密码和凭证版本均不变化。 */
    @Test
    void shouldPreservePasswordOnResetCodeFailure() throws Exception {
        for (var error :
                List.of(
                        com.ginkgo.foodmap.user.exception.VerificationCodeErrorCode
                                .INVALID_OR_EXPIRED,
                        com.ginkgo.foodmap.user.exception.VerificationCodeErrorCode
                                .ATTEMPTS_EXCEEDED,
                        com.ginkgo.foodmap.user.exception.VerificationCodeErrorCode
                                .SERVICE_UNAVAILABLE)) {
            doThrow(new com.ginkgo.foodmap.common.exception.BusinessException(error))
                    .when(codes)
                    .consume(
                            VerificationCodePurpose.PASSWORD_RESET,
                            "student@std.uestc.edu.cn",
                            "012345");
            mvc.perform(
                            post("/auth/password-reset")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                    {"email":"student@std.uestc.edu.cn","verificationCode":"012345",
                                     "newPassword":"NewPassword2026"}
                                    """))
                    .andExpect(status().is(error.getHttpStatus().value()))
                    .andExpect(jsonPath("$.code").value(error.getCode()));
        }
        assertThat(users.selectById(user.getId()).getPasswordHash())
                .isEqualTo(user.getPasswordHash());
        assertThat(users.selectById(user.getId()).getCredentialVersion()).isZero();
        verifyNoInteractions(sessions);
    }

    private org.springframework.test.web.servlet.ResultActions loginRequest() throws Exception {
        return mvc.perform(
                post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\" ginkgo_user \",\"password\":\"Ginkgo2026\"}"));
    }

    private String login() throws Exception {
        String body =
                loginRequest()
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).path("data").path("accessToken").asText();
    }

    /** 只存在于测试上下文，用于验证方法级角色授权。 */
    @RestController
    static class AdminProbe {
        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/api/test/admin")
        public Map<String, Boolean> admin() {
            return Map.of("allowed", true);
        }
    }
}
