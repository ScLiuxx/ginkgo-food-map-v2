package com.foodmap.user.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.user.config.VerificationMailProperties;
import com.foodmap.user.config.VerificationCodeProperties;
import com.foodmap.user.config.VerificationCodeRedisScripts;
import com.foodmap.user.domain.VerificationCodePurpose;
import com.foodmap.user.dto.VerificationCodeReservation;
import com.foodmap.user.exception.VerificationCodeErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用本机 Redis 4 号库验证 Lua 原子性和真实序列化行为。
 *
 * <p>测试仅在显式传入 {@code -Dredis.integration=true} 时运行。每个测试使用随机键前缀并只删除
 * 自己创建的键，禁止使用 FLUSHDB，以免影响开发者在同一 Redis 数据库中的其他数据。</p>
 */
@EnabledIfSystemProperty(named = "redis.integration", matches = "true")
class VerificationCodeServiceRedisIntegrationTest {

    private static final String HMAC_SECRET = "redis-integration-test-secret-at-least-32-characters";

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private VerificationCodeService service;
    private String keyPrefix;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration redisConfiguration = new RedisStandaloneConfiguration("localhost", 6379);
        redisConfiguration.setDatabase(4);
        connectionFactory = new LettuceConnectionFactory(redisConfiguration);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        keyPrefix = "test:auth:verification-code:{verification-code}:" + UUID.randomUUID();
        VerificationCodeProperties properties = properties(keyPrefix);
        service = new VerificationCodeService(
                redisTemplate,
                properties,
                new VerificationCodeRedisScripts(
                        script("redis/verification-code-reserve.lua"),
                        script("redis/verification-code-activate.lua"),
                        script("redis/verification-code-cancel.lua"),
                        script("redis/verification-code-consume.lua")
                ),
                new com.foodmap.user.mail.VerificationMailSender(org.mockito.Mockito.mock(JavaMailSender.class), properties, mailProperties())
        );
    }

    @AfterEach
    void tearDown() {
        if (redisTemplate != null && keyPrefix != null) {
            Set<String> keys = redisTemplate.keys(keyPrefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /** 验证 Redis 键和值均不包含明文邮箱或验证码，敏感对象的 toString 也会脱敏。 */
    @Test
    void shouldNeverStoreOrPrintPlaintextSecrets() {
        String email = "Sensitive@std.uestc.edu.cn";
        VerificationCodeReservation reservation = service.reserve(VerificationCodePurpose.REGISTER, email);

        Set<String> keys = redisTemplate.keys(keyPrefix + "*");
        assertThat(keys).isNotNull().isNotEmpty();
        assertThat(keys).allSatisfy(key -> {
            assertThat(key).doesNotContainIgnoringCase(email).doesNotContain(reservation.getCode());
        });

        String codeKey = keys.stream().filter(key -> key.contains(":code:")).findFirst().orElseThrow();
        Map<Object, Object> storedCode = redisTemplate.opsForHash().entries(codeKey);
        assertThat(storedCode.values()).allSatisfy(value -> assertThat(value.toString())
                .doesNotContainIgnoringCase(email)
                .doesNotContain(reservation.getCode()));
        assertThat(reservation.toString()).doesNotContainIgnoringCase(email).doesNotContain(reservation.getCode());
    }

    /** 验证 pending 验证码不可使用，取消后可重新发送，同时小时配额不会回退。 */
    @Test
    void shouldRequireActivationAndKeepQuotaAfterCancellation() {
        String email = "pending@std.uestc.edu.cn";
        VerificationCodeReservation first = service.reserve(VerificationCodePurpose.REGISTER, email);

        assertErrorCode(
                () -> service.consume(VerificationCodePurpose.REGISTER, email, first.getCode()),
                VerificationCodeErrorCode.INVALID_OR_EXPIRED
        );
        service.cancel(first);

        VerificationCodeReservation second = service.reserve(
                VerificationCodePurpose.REGISTER,
                email
        );
        service.cancel(second);

        assertErrorCode(
                () -> service.reserve(VerificationCodePurpose.REGISTER, email),
                VerificationCodeErrorCode.EMAIL_RATE_LIMITED
        );
    }

    /** 验证注册验证码不能用于密码重置，且成功消费后无法再次使用。 */
    @Test
    void shouldIsolatePurposeAndConsumeOnlyOnce() {
        String email = "purpose@std.uestc.edu.cn";
        VerificationCodeReservation reservation = service.reserve(
                VerificationCodePurpose.REGISTER,
                email
        );
        service.activate(reservation);

        assertErrorCode(
                () -> service.consume(VerificationCodePurpose.PASSWORD_RESET, email, reservation.getCode()),
                VerificationCodeErrorCode.INVALID_OR_EXPIRED
        );
        service.consume(VerificationCodePurpose.REGISTER, email, reservation.getCode());
        assertErrorCode(
                () -> service.consume(VerificationCodePurpose.REGISTER, email, reservation.getCode()),
                VerificationCodeErrorCode.INVALID_OR_EXPIRED
        );
    }

    /** 验证达到错误次数上限后验证码立即失效，正确验证码也不能恢复使用。 */
    @Test
    void shouldInvalidateCodeAfterMaximumFailedAttempts() {
        String email = "attempts@std.uestc.edu.cn";
        VerificationCodeReservation reservation = service.reserve(
                VerificationCodePurpose.REGISTER,
                email
        );
        service.activate(reservation);
        String wrongCode = reservation.getCode().equals("000000") ? "000001" : "000000";

        for (int attempt = 1; attempt < 3; attempt++) {
            assertErrorCode(
                    () -> service.consume(VerificationCodePurpose.REGISTER, email, wrongCode),
                    VerificationCodeErrorCode.INVALID_OR_EXPIRED
            );
        }
        assertErrorCode(
                () -> service.consume(VerificationCodePurpose.REGISTER, email, wrongCode),
                VerificationCodeErrorCode.ATTEMPTS_EXCEEDED
        );
        assertErrorCode(
                () -> service.consume(VerificationCodePurpose.REGISTER, email, reservation.getCode()),
                VerificationCodeErrorCode.INVALID_OR_EXPIRED
        );
    }

    /** 验证两个并发请求争用同一验证码时，Lua 只允许一个请求成功消费。 */
    @Test
    void shouldAllowOnlyOneConcurrentConsumer() throws Exception {
        String email = "concurrent@std.uestc.edu.cn";
        VerificationCodeReservation reservation = service.reserve(
                VerificationCodePurpose.REGISTER,
                email
        );
        service.activate(reservation);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> consumeAfterSignal(ready, start, email, reservation.getCode())),
                    executor.submit(() -> consumeAfterSignal(ready, start, email, reservation.getCode()))
            );
            ready.await();
            start.countDown();

            assertThat(results).extracting(future -> future.get()).containsExactlyInAnyOrder(true, false);
        }
    }

    private boolean consumeAfterSignal(CountDownLatch ready, CountDownLatch start, String email, String code)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            service.consume(VerificationCodePurpose.REGISTER, email, code);
            return true;
        } catch (BusinessException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(VerificationCodeErrorCode.INVALID_OR_EXPIRED);
            return false;
        }
    }

    private static void assertErrorCode(Runnable invocation, VerificationCodeErrorCode expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static DefaultRedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }

    private static VerificationCodeProperties properties(String keyPrefix) {
        VerificationCodeProperties properties = new VerificationCodeProperties();
        properties.setLength(6);
        properties.setTimeToLive(Duration.ofMinutes(5));
        properties.setResendCooldown(Duration.ofSeconds(60));
        properties.setMaxAttempts(3);
        properties.setEmailHourlyLimit(2);
        properties.setEmailDailyLimit(10);
        properties.setHmacSecret(HMAC_SECRET);
        properties.setKeyPrefix(keyPrefix);
        return properties;
    }

    private static VerificationMailProperties mailProperties() {
        VerificationMailProperties properties = new VerificationMailProperties();
        properties.setFrom("no-reply@example.test");
        properties.setSubjectPrefix("[测试]");
        return properties;
    }
}
