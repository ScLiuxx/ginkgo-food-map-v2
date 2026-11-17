package com.foodmap.user.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.user.config.VerificationMailProperties;
import com.foodmap.user.config.VerificationCodeProperties;
import com.foodmap.user.config.VerificationCodeRedisScripts;
import com.foodmap.user.domain.VerificationCodePurpose;
import com.foodmap.user.dto.VerificationCodeReservation;
import com.foodmap.user.exception.VerificationCodeErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 不依赖外部 Redis 的验证码组件快速测试，真实 Lua 原子性由 Redis 集成测试覆盖。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class VerificationCodeServiceTest {

    private StringRedisTemplate redisTemplate;
    private JavaMailSender mailSender;
    private VerificationCodeService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        mailSender = mock(JavaMailSender.class);
        VerificationCodeProperties properties = properties(
                "test:auth:verification-code:{verification-code}"
        );
        service = new VerificationCodeService(
                redisTemplate,
                properties,
                new VerificationCodeRedisScripts(mockScript(), mockScript(), mockScript(), mockScript()),
                new com.foodmap.user.mail.VerificationMailSender(mailSender, properties, mailProperties())
        );
    }

    /** 验证 SecureRandom 生成固定长度数字码，且敏感预留对象不会自动输出明文。 */
    @Test
    void shouldGenerateNumericCodeWithoutLeakingItFromToString() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        VerificationCodeReservation reservation = service.reserve(
                VerificationCodePurpose.REGISTER,
                "Student@std.uestc.edu.cn"
        );

        assertThat(reservation.getCode()).matches("\\d{6}");
        assertThat(reservation.getRecipientEmail()).isEqualTo("student@std.uestc.edu.cn");
        assertThat(reservation.toString())
                .doesNotContain(reservation.getCode())
                .doesNotContainIgnoringCase(reservation.getRecipientEmail());
    }

    /** 验证 Redis 不可用时失败关闭，调用方不能把基础设施故障当成验证成功。 */
    @Test
    void shouldFailClosedWhenRedisIsUnavailable() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("test-only failure"));

        assertThatThrownBy(() -> service.consume(
                VerificationCodePurpose.REGISTER,
                "student@std.uestc.edu.cn",
                "123456"
        )).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(VerificationCodeErrorCode.SERVICE_UNAVAILABLE));
    }

    /** 验证邮件发送成功后激活验证码，并且邮件正文包含用途、验证码和有效期。 */
    @Test
    void shouldSendMailAndActivateReservation() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, 1L);

        service.send(VerificationCodePurpose.REGISTER, "student@std.uestc.edu.cn");

        org.mockito.ArgumentCaptor<SimpleMailMessage> captor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("no-reply@example.test");
        assertThat(message.getTo()).containsExactly("student@std.uestc.edu.cn");
        assertThat(message.getSubject()).isEqualTo("[测试]注册验证码");
        assertThat(message.getText()).contains("注册操作").containsPattern("\\d{6}").contains("5分钟内有效");
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    /** 验证 SMTP 失败会取消 pending 验证码并返回稳定的服务不可用错误。 */
    @Test
    void shouldCancelReservationWhenMailDeliveryFails() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, 1L);
        doThrow(new MailSendException("test-only failure"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> service.send(
                VerificationCodePurpose.PASSWORD_RESET,
                "student@std.uestc.edu.cn"
        )).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(VerificationCodeErrorCode.MAIL_DELIVERY_FAILED));

        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    private static RedisScript<Long> mockScript() {
        return mock(RedisScript.class);
    }

    private static VerificationCodeProperties properties(String keyPrefix) {
        VerificationCodeProperties properties = new VerificationCodeProperties();
        properties.setLength(6);
        properties.setTimeToLive(Duration.ofMinutes(5));
        properties.setResendCooldown(Duration.ofSeconds(60));
        properties.setMaxAttempts(5);
        properties.setEmailHourlyLimit(5);
        properties.setEmailDailyLimit(10);
        properties.setHmacSecret("unit-test-hmac-secret-at-least-32-characters");
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
