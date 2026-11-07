package com.ginkgo.foodmap.security;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.security.config.AuthenticationProperties;
import com.ginkgo.foodmap.security.service.LoginProtectionService;
import com.ginkgo.foodmap.security.service.LoginSessionService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** 显式启用 redis.integration 后验证真实 Lua、会话 TTL 和撤销；只清理随机生成的测试键。 */
@EnabledIfSystemProperty(named = "redis.integration", matches = "true")
class LoginRedisIntegrationTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private LoginProtectionService protection;
    private LoginSessionService sessions;
    private final List<String> ownedKeys = new ArrayList<>();

    @BeforeEach
    void connect() {
        var config = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        config.setDatabase(4);
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        var properties = new AuthenticationProperties();
        var limits = new AuthenticationProperties.LoginProtection();
        limits.setMaxFailedAttempts(3);
        limits.setFailureWindow(Duration.ofSeconds(5));
        limits.setLockDuration(Duration.ofSeconds(1));
        properties.setLoginProtection(limits);
        protection = new LoginProtectionService(redis, properties);
        sessions = new LoginSessionService(redis);
    }

    /** 不执行 FLUSHDB 或通配符删除，避免影响本地业务会话和验证码。 */
    @AfterEach
    void clean() {
        if (redis != null && !ownedKeys.isEmpty()) {
            redis.delete(ownedKeys);
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    /** 并发失败不会丢失计数，达到阈值会锁定，锁超时后允许重试。 */
    @Test
    void shouldAtomicallyCountFailuresAndExpireLock() throws Exception {
        String key = protection.key(null, "it_" + UUID.randomUUID());
        ownedKeys.addAll(List.of(key + ":failures", key + ":lock"));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 8; index++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return protection.failed(key);
                                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get();
            }
        }
        assertThat(redis.opsForValue().get(key + ":failures")).isEqualTo("8");
        assertThatThrownBy(() -> protection.check(key)).isInstanceOf(BusinessException.class);
        assertThat(redis.getExpire(key + ":failures")).isBetween(1L, 5L);
        await().atMost(Duration.ofSeconds(3))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(key + ":lock")));
        assertThatCode(() -> protection.check(key)).doesNotThrowAnyException();
        protection.succeeded(key);
        assertThat(redis.hasKey(key + ":failures")).isFalse();
        assertThat(protection.key(42L, "ALIAS")).isEqualTo(protection.key(42L, "alias"));
    }

    /** 会话绑定用户与凭证版本，删除其中一个不影响同一用户的其他会话。 */
    @Test
    void shouldBindSessionToIdentityAndRevokeIndependently() {
        String first = sessionId();
        String second = sessionId();
        sessions.create(first, 42L, 2, Instant.now().plusSeconds(30));
        sessions.create(second, 42L, 2, Instant.now().plusSeconds(30));
        assertThat(sessions.isActive(first, 42L, 2)).isTrue();
        assertThat(sessions.isActive(first, 43L, 2)).isFalse();
        assertThat(sessions.isActive(first, 42L, 3)).isFalse();
        assertThat(redis.opsForValue().get("auth:session:" + first)).isEqualTo("42:2");
        assertThat(redis.getExpire("auth:session:" + first)).isBetween(1L, 30L);
        sessions.revoke(first);
        sessions.revoke(first);
        assertThat(sessions.isActive(first, 42L, 2)).isFalse();
        assertThat(sessions.isActive(second, 42L, 2)).isTrue();
    }

    /** 无需退出操作，达到令牌截止时间后 Redis 会话自行失效。 */
    @Test
    void shouldExpireSessionAutomatically() {
        String id = sessionId();
        sessions.create(id, 42L, 0, Instant.now().plusSeconds(1));
        assertThat(sessions.isActive(id, 42L, 0)).isTrue();
        await().atMost(Duration.ofSeconds(3)).until(() -> !sessions.isActive(id, 42L, 0));
    }

    private String sessionId() {
        String id = UUID.randomUUID().toString();
        ownedKeys.add("auth:session:" + id);
        return id;
    }
}
