package com.ginkgo.foodmap.security.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.dto.LoginRequest;
import com.ginkgo.foodmap.security.exception.AuthenticationErrorCode;
import com.ginkgo.foodmap.security.vo.CurrentUser;
import com.ginkgo.foodmap.security.vo.LoginResponse;
import com.ginkgo.foodmap.user.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 协调密码校验、失败限流、令牌签发及独立会话；不直接访问 user 模块的 Mapper。 */
@Service
@RequiredArgsConstructor
public class LoginService {
    private final UserAccountService accounts;
    private final LoginProtectionService protection;
    private final LoginSessionService sessions;
    private final JwtEncoder encoder;
    private final AuthenticationProperties properties;

    /** 对外提供当前用户快照，状态或版本在认证后发生变化时仍拒绝过期身份。 */
    public CurrentUser currentUser(long userId, long version) {
        try {
            return accounts.findById(userId).filter(identity -> identity.isLoginAllowed()
                            && identity.getCredentialVersion() == version).map(CurrentUser::from)
                    .orElseThrow(() -> new BusinessException(AuthenticationErrorCode.INVALID_CREDENTIALS));
        } catch (DataAccessException exception) {
            throw new BusinessException(AuthenticationErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** 删除当前会话，存储故障由统一异常处理器返回503，绝不宣告退出成功。 */
    public void logout(String sessionId) {
        try {
            sessions.revoke(sessionId);
        } catch (DataAccessException exception) {
            throw new BusinessException(AuthenticationErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** Redis 写入成功后才返回凭证，防止向客户端返回无法使用或未受会话约束的令牌。 */
    public LoginResponse login(LoginRequest request) {
        String accountInput = request.getUsername().trim();
        try {
            var identity = accounts.findByLoginAccount(accountInput);
            String key = protection.key(identity.map(user -> user.getId()).orElse(null), accountInput);
            protection.check(key);
            // BCrypt 只接受最多72字节，超长 Unicode 密码直接失败，不允许截断后匹配。
            var user = request.getPassword().getBytes(StandardCharsets.UTF_8).length <= 72
                    ? accounts.authenticate(accountInput, request.getPassword()) : java.util.Optional
                    .<com.ginkgo.foodmap.user.dto.UserIdentity>empty();
            if (user.isEmpty()) {
                throw new BusinessException(protection.failed(key) ? AuthenticationErrorCode.LOGIN_LOCKED
                        : AuthenticationErrorCode.INVALID_CREDENTIALS);
            }
            var account = user.orElseThrow();
            Instant now = Instant.now();
            Instant expiresAt = now.plus(properties.getJwt().getTimeToLive()).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            String sessionId = UUID.randomUUID().toString();
            var claims = JwtClaimsSet.builder().issuer(properties.getJwt().getIssuer())
                    .audience(List.of(properties.getJwt().getAudience())).subject(account.getId().toString())
                    .issuedAt(now).expiresAt(expiresAt).id(sessionId)
                    .claim("credential_version", account.getCredentialVersion()).build();
            String token = encoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
            protection.succeeded(key);
            sessions.create(sessionId, account.getId(), account.getCredentialVersion(), expiresAt);
            return new LoginResponse(token, "Bearer", expiresAt, CurrentUser.from(account));
        } catch (DataAccessException exception) {
            throw new BusinessException(AuthenticationErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
