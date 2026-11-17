package com.foodmap.security.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.security.config.AuthenticationProperties;
import com.foodmap.security.exception.AuthenticationErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** 单账号密码错误限流；失败计数与锁定由 Lua 原子更新，不保存原始用户名或密码。 */
@Service
@RequiredArgsConstructor
public class LoginProtectionService {
    private final StringRedisTemplate redis;
    private final AuthenticationProperties properties;
    private static final DefaultRedisScript<Long> FAILURE = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if count >= tonumber(ARGV[2]) then
                redis.call('SET', KEYS[2], '1', 'PX', ARGV[3], 'NX')
                return 1
            end
            return 0
            """, Long.class);

    /** 已存在账号按稳定 ID 计数，不存在账号只保存用户名的 SHA-256 摘要。 */
    public String key(Long userId, String username) {
        String identity = userId == null ? "name:" + username.toLowerCase(Locale.ROOT) : "id:" + userId;
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
            return "auth:login:{" + digest + "}";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    /** Redis 不可用时由上层中止登录，不能退化成不带限流的密码校验。 */
    public void check(String key) {
        Boolean locked = redis.hasKey(key + ":lock");
        if (locked == null) {
            throw new BusinessException(AuthenticationErrorCode.SERVICE_UNAVAILABLE);
        }
        if (locked) {
            throw new BusinessException(AuthenticationErrorCode.LOGIN_LOCKED);
        }
    }

    /** 返回是否达到临时锁定阈值；固定失败窗口不会被重复失败不断续期。 */
    public boolean failed(String key) {
        var config = properties.getLoginProtection();
        Long result = redis.execute(FAILURE, List.of(key + ":failures", key + ":lock"),
                Long.toString(config.getFailureWindow().toMillis()),
                Integer.toString(config.getMaxFailedAttempts()), Long.toString(config.getLockDuration().toMillis()));
        if (result == null) {
            throw new BusinessException(AuthenticationErrorCode.SERVICE_UNAVAILABLE);
        }
        return result == 1L;
    }

    /** 成功登录清除错误计数；已被并发失败建立的锁保留，不能由较早请求撤销。 */
    public void succeeded(String key) {
        redis.delete(key + ":failures");
    }
}
