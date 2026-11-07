package com.ginkgo.foodmap.security.service;

import lombok.RequiredArgsConstructor;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/** 每次登录拥有独立 Redis 会话；删除当前 jti 不影响其他设备，过期会话由 TTL 自动回收。 */
@Service
@RequiredArgsConstructor
public class LoginSessionService {
    private final StringRedisTemplate redis;

    /** 仅记录用户 ID 与凭证版本，不记录完整 JWT。 */
    public void create(String sessionId, Long userId, long version, Instant expiresAt) {
        redis.opsForValue()
                .set(
                        key(sessionId),
                        value(userId, version),
                        Duration.between(Instant.now(), expiresAt));
    }

    /** JWT 签名正确仍必须找到对应会话，Redis 故障绝不能放行请求。 */
    public boolean isActive(String sessionId, Long userId, long version) {
        return value(userId, version).equals(redis.opsForValue().get(key(sessionId)));
    }

    /** 幂等删除当前会话，不扫描或删除其他设备的键。 */
    public void revoke(String sessionId) {
        redis.delete(key(sessionId));
    }

    private String key(String sessionId) {
        return "auth:session:" + sessionId;
    }

    private String value(Long userId, long version) {
        return userId + ":" + version;
    }
}
