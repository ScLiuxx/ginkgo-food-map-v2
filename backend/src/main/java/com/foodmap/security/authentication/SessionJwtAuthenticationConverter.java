package com.foodmap.security.authentication;

import com.foodmap.user.service.UserAccountService;
import com.foodmap.security.service.LoginSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** 签名通过后继续校验 Redis 会话及最新账号数据；授权角色来自数据库，避免旧令牌保留旧权限。 */
@Component
@RequiredArgsConstructor
public class SessionJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final LoginSessionService sessions;
    private final UserAccountService accounts;

    /** 基础设施错误时拒绝认证，不使用过期缓存或仅依赖 JWT 签名降级放行。 */
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            long userId = Long.parseLong(jwt.getSubject());
            long version = jwt.getClaim("credential_version");
            var user = accounts.findById(userId).filter(identity -> identity.isLoginAllowed()
                    && identity.getCredentialVersion() == version).orElseThrow(this::invalid);
            if (!sessions.isActive(jwt.getId(), userId, version)) {
                throw invalid();
            }
            return new JwtAuthenticationToken(jwt,
                    user.getRoles().stream().map(SimpleGrantedAuthority::new).toList(), user.getUsername());
        } catch (DataAccessException exception) {
            // 临时基础设施故障返回503，客户端保留现有凭证以便恢复后重试。
            throw new OAuth2AuthenticationException(new OAuth2Error("temporarily_unavailable"));
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private InvalidBearerTokenException invalid() {
        return new InvalidBearerTokenException("登录状态无效或暂不可用，请重新登录");
    }
}
