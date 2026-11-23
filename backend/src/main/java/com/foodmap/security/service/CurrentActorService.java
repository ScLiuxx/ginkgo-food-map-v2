package com.foodmap.security.service;

import com.foodmap.security.dto.AuthenticatedActor;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/** 向业务模块提供经过JWT、会话及最新账号校验的身份，不暴露用户Mapper或凭证内容。 */
@Service
@RequiredArgsConstructor
public class CurrentActorService {
    /** 管理员能力只看认证转换器从数据库加载的权限，忽略JWT自报的roles或请求体字段。 */
    public AuthenticatedActor requireActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("请先登录");
        }
        try {
            long id = Long.parseLong(token.getToken().getSubject());
            if (id <= 0) throw new NumberFormatException();
            boolean administrator = token.getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
            return new AuthenticatedActor(id, administrator);
        } catch (NumberFormatException exception) {
            throw new AuthenticationCredentialsNotFoundException("登录身份无效");
        }
    }
}
