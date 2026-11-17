package com.foodmap.user.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.user.config.VerificationCodeProperties;
import com.foodmap.user.config.VerificationCodeRedisScripts;
import com.foodmap.user.domain.VerificationCodePurpose;
import com.foodmap.user.dto.VerificationCodeReservation;
import com.foodmap.user.exception.VerificationCodeErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 邮箱验证码的生成、Redis 限流、投递状态和一次性消费组件。
 *
 * <p>Redis 从不保存明文验证码或邮箱：验证码使用服务端密钥计算 HMAC，邮箱也转换为
 * 不可枚举的 HMAC 标识。所有涉及多个键的状态变化均由 Lua 原子执行，避免并发发送、并发验证时
 * 绕过冷却、限额或一次性消费约束。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Duration HOURLY_WINDOW = Duration.ofHours(1);
    private static final Duration DAILY_WINDOW = Duration.ofDays(1);

    private final StringRedisTemplate redisTemplate;
    private final VerificationCodeProperties properties;
    private final VerificationCodeRedisScripts scripts;
    private final com.foodmap.user.mail.VerificationMailSender mailSender;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 完成验证码发送的完整状态流程。
     *
     * <p>先在 Redis 原子预留额度和 pending 验证码，再调用 SMTP。发送成功后才激活验证码；
     * 发送失败则按 reservationId 取消本次验证码和冷却状态，但保留频率计数，防止利用 SMTP
     * 故障绕过发送限额。任何异常日志均不包含邮箱或验证码。</p>
     *
     * @param purpose  验证码用途
     * @param email    收件邮箱
     */
    public void send(VerificationCodePurpose purpose, String email) {
        VerificationCodeReservation reservation = reserve(purpose, email);
        try {
            mailSender.send(reservation);
        } catch (BusinessException exception) {
            cancelAfterMailFailure(reservation);
            log.error("Verification code mail delivery failed; exceptionType={}", exception.getClass().getName());
            throw new BusinessException(VerificationCodeErrorCode.MAIL_DELIVERY_FAILED);
        }
        activate(reservation);
    }

    /**
     * 原子预留一次验证码发送资格。
     *
     * <p>成功后验证码处于 pending 状态，尚不能验证。邮件发送成功后必须调用 {@link #activate}；
     * 发送失败必须调用 {@link #cancel}。取消只移除本次验证码和冷却键，已经计入的小时/24 小时配额
     * 不回退，防止攻击者利用故障反复触发 SMTP。</p>
     *
     * @param purpose  验证码用途
     * @param email    收件邮箱
     * @return 含明文验证码的短生命周期预留对象，禁止记录日志
     */
    public VerificationCodeReservation reserve(VerificationCodePurpose purpose, String email) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        String normalizedEmail = normalizeEmail(email);
        String emailId = identifier("email", normalizedEmail);
        String code = generateNumericCode();
        String reservationId = UUID.randomUUID().toString();
        String digest = codeDigest(purpose, normalizedEmail, code);

        Long result = execute(
                scripts.getReserve(),
                List.of(
                        codeKey(purpose, emailId),
                        cooldownKey(emailId),
                        emailHourlyKey(emailId),
                        emailDailyKey(emailId)
                ),
                digest,
                reservationId,
                millis(properties.getTimeToLive()),
                millis(properties.getResendCooldown()),
                Integer.toString(properties.getEmailHourlyLimit()),
                Integer.toString(properties.getEmailDailyLimit()),
                millis(HOURLY_WINDOW),
                millis(DAILY_WINDOW)
        );
        throwIfReservationRejected(result);
        return new VerificationCodeReservation(purpose, normalizedEmail, code, reservationId);
    }

    /**
     * 邮件投递成功后激活验证码。预留已过期或已被替换时拒绝激活，避免旧邮件恢复新状态。
     */
    public void activate(VerificationCodeReservation reservation) {
        Objects.requireNonNull(reservation, "reservation must not be null");
        String emailId = identifier("email", reservation.getRecipientEmail());
        Long result = execute(
                scripts.getActivate(),
                List.of(codeKey(reservation.getPurpose(), emailId)),
                reservation.getReservationId()
        );
        if (result != 1L) {
            throw new BusinessException(VerificationCodeErrorCode.DELIVERY_STATE_EXPIRED);
        }
    }

    /**
     * 邮件投递失败后取消仍处于 pending 的本次预留。
     *
     * <p>Lua 会核对 reservationId，因此延迟到达的失败回调不会删除后续请求生成的新验证码。</p>
     */
    public void cancel(VerificationCodeReservation reservation) {
        Objects.requireNonNull(reservation, "reservation must not be null");
        String emailId = identifier("email", reservation.getRecipientEmail());
        execute(
                scripts.getCancel(),
                List.of(codeKey(reservation.getPurpose(), emailId), cooldownKey(emailId)),
                reservation.getReservationId()
        );
    }

    /**
     * 原子校验并消费验证码。验证成功时立即删除 Redis 记录，因此同一验证码只能成功一次。
     *
     * @param purpose       必须与发送时用途一致
     * @param email         接收验证码的邮箱
     * @param submittedCode 用户提交的数字验证码
     */
    public void consume(VerificationCodePurpose purpose, String email, String submittedCode) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        String normalizedEmail = normalizeEmail(email);
        String code = requireText(submittedCode, "submittedCode").trim();
        String emailId = identifier("email", normalizedEmail);
        String digest = codeDigest(purpose, normalizedEmail, code);

        Long result = execute(
                scripts.getConsume(),
                List.of(codeKey(purpose, emailId)),
                digest,
                Integer.toString(properties.getMaxAttempts())
        );
        if (result == 1L) {
            return;
        }
        if (result == -2L) {
            throw new BusinessException(VerificationCodeErrorCode.ATTEMPTS_EXCEEDED);
        }
        throw new BusinessException(VerificationCodeErrorCode.INVALID_OR_EXPIRED);
    }

    private Long execute(RedisScript<Long> script, List<String> keys, String... arguments) {
        try {
            Long result = redisTemplate.execute(script, keys, (Object[]) arguments);
            if (result == null) {
                throw new BusinessException(VerificationCodeErrorCode.SERVICE_UNAVAILABLE);
            }
            return result;
        } catch (DataAccessException exception) {
            // 不记录键、邮箱、验证码或异常消息，避免基础设施异常携带敏感数据。
            log.error("Verification code Redis operation failed; exceptionType={}", exception.getClass().getName());
            throw new BusinessException(VerificationCodeErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private void cancelAfterMailFailure(VerificationCodeReservation reservation) {
        try {
            cancel(reservation);
        } catch (BusinessException exception) {
            // 取消失败时 pending 验证码仍不可使用并会按 TTL 过期；只记录类型，避免掩盖原始邮件故障。
            log.error("Verification code reservation cancellation failed; errorCode={}",
                    exception.getErrorCode().getCode());
        }
    }

    private void throwIfReservationRejected(long result) {
        switch ((int) result) {
            case 1 -> {
                return;
            }
            case -1 -> throw new BusinessException(VerificationCodeErrorCode.RESEND_COOLDOWN);
            case -2, -3 -> throw new BusinessException(VerificationCodeErrorCode.EMAIL_RATE_LIMITED);
            default -> throw new BusinessException(VerificationCodeErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private String generateNumericCode() {
        int bound = powerOfTen(properties.getLength());
        return String.format(Locale.ROOT, "%0" + properties.getLength() + "d", secureRandom.nextInt(bound));
    }

    private String identifier(String type, String value) {
        return hmac(type + '\0' + value);
    }

    private String codeDigest(VerificationCodePurpose purpose, String email, String code) {
        return hmac("code" + '\0' + purpose.getKeySegment() + '\0' + email + '\0' + code);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getHmacSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("JVM does not support required HMAC algorithm", exception);
        }
    }

    private String codeKey(VerificationCodePurpose purpose, String emailId) {
        return properties.getKeyPrefix() + ":code:" + purpose.getKeySegment() + ':' + emailId;
    }

    private String cooldownKey(String emailId) {
        return properties.getKeyPrefix() + ":cooldown:" + emailId;
    }

    private String emailHourlyKey(String emailId) {
        return properties.getKeyPrefix() + ":limit:email:hour:" + emailId;
    }

    private String emailDailyKey(String emailId) {
        return properties.getKeyPrefix() + ":limit:email:day:" + emailId;
    }

    private static String normalizeEmail(String email) {
        return requireText(email, "email").trim().toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static int powerOfTen(int exponent) {
        int value = 1;
        for (int index = 0; index < exponent; index++) {
            value = Math.multiplyExact(value, 10);
        }
        return value;
    }

    private static String millis(Duration duration) {
        long value = Objects.requireNonNull(duration, "duration must not be null").toMillis();
        if (value <= 0) {
            throw new IllegalArgumentException("duration must be positive");
        }
        return Long.toString(value);
    }
}
