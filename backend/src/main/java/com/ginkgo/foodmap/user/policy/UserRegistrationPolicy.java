package com.ginkgo.foodmap.user.policy;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.user.config.UserRegistrationProperties;
import com.ginkgo.foodmap.user.exception.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 集中执行账号输入规则，保证发送验证码、注册与密码重置使用同一套校园邮箱和密码策略。
 *
 * <p>这些检查属于服务端业务边界。前端即使绕过页面限制直接调用接口，也不能注册非校园邮箱、
 * 非法用户名或过弱密码。数据库唯一约束仍负责并发情况下的最后一道防线。</p>
 */
@Service
@RequiredArgsConstructor
public class UserRegistrationPolicy {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[\\p{IsHan}a-zA-Z0-9_]+");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("[\\x21-\\x7e]+");

    private final UserRegistrationProperties properties;

    /**
     * 规范化并精确校验校园邮箱域名。
     *
     * @param email 用户提交的完整邮箱
     * @return 去除首尾空白并转换为小写的邮箱
     */
    public String normalizeCampusEmail(String email) {
        Objects.requireNonNull(email, "email must not be null");
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('@');
        if (separator <= 0 || separator != normalized.lastIndexOf('@') || separator == normalized.length() - 1) {
            throw new BusinessException(UserErrorCode.CAMPUS_EMAIL_REQUIRED);
        }

        String domain = normalized.substring(separator + 1);
        boolean allowed = properties.getAllowedEmailDomains().stream()
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(domain::equals);
        if (!allowed) {
            throw new BusinessException(UserErrorCode.CAMPUS_EMAIL_REQUIRED);
        }
        return normalized;
    }

    /**
     * 规范化用户名并限制为文字、数字和下划线。
     *
     * <p>长度按 Unicode 字符计算，使中文用户名不会因 UTF-16 代理对而被错误计数。</p>
     *
     * @param username 用户提交的用户名
     * @return 去除首尾空白后的用户名
     */
    public String normalizeUsername(String username) {
        Objects.requireNonNull(username, "username must not be null");
        String normalized = username.trim();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < properties.getUsernameMinLength()
                || length > properties.getUsernameMaxLength()
                || !USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(UserErrorCode.USERNAME_INVALID,
                    "用户名需为" + properties.getUsernameMinLength() + "–" + properties.getUsernameMaxLength()
                            + "位，仅支持中文、英文字母、数字和下划线，区分大小写");
        }
        return normalized;
    }

    /**
     * 校验4–32位英文可打印字符，不强制字符组合。
     *
     * <p>不做 trim，含空格、中文或控制字符的输入直接拒绝。ASCII最长32字节，小于BCrypt
     * 的72字节上限。新规则只用于注册与重置，不追溯限制历史密码登录。</p>
     *
     * @param password 用户提交的明文密码，仅在当前请求内短暂存在
     */
    public void validatePassword(String password) {
        Objects.requireNonNull(password, "password must not be null");
        int characterCount = password.codePointCount(0, password.length());
        if (characterCount < properties.getPasswordMinLength()
                || characterCount > properties.getPasswordMaxLength()
                || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new BusinessException(UserErrorCode.PASSWORD_WEAK,
                    "密码需为" + properties.getPasswordMinLength() + "–" + properties.getPasswordMaxLength()
                            + "位，仅支持英文字母、数字和英文符号，不允许中文或空格");
        }
    }
}
