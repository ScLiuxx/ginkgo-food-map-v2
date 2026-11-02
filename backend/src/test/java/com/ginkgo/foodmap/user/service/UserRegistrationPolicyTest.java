package com.ginkgo.foodmap.user.service;
import com.ginkgo.foodmap.user.policy.UserRegistrationPolicy;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.user.config.UserRegistrationProperties;
import com.ginkgo.foodmap.user.exception.UserErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用户注册输入策略测试，确保关键规则不依赖前端校验。
 */
class UserRegistrationPolicyTest {

    private UserRegistrationPolicy policy;

    @BeforeEach
    void setUp() {
        UserRegistrationProperties properties = new UserRegistrationProperties();
        properties.setAllowedEmailDomains(List.of("std.uestc.edu.cn"));
        properties.setUsernameMinLength(2);
        properties.setUsernameMaxLength(24);
        properties.setPasswordMinLength(4);
        properties.setPasswordMaxBytes(72);
        policy = new UserRegistrationPolicy(properties);
    }

    /** 校园邮箱域名必须完全匹配，不能用相似后缀绕过。 */
    @Test
    void shouldNormalizeExactCampusEmail() {
        assertThat(policy.normalizeCampusEmail(" Student@STD.UESTC.EDU.CN "))
                .isEqualTo("student@std.uestc.edu.cn");

        assertThatThrownBy(() -> policy.normalizeCampusEmail("student@std.uestc.edu.cn.example.com"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(UserErrorCode.CAMPUS_EMAIL_REQUIRED));
    }

    /** 用户名允许中文、字母、数字和下划线，但拒绝空格和标点。 */
    @Test
    void shouldValidateUsernameCharactersAndLength() {
        assertThat(policy.normalizeUsername(" 银杏同学_01 ")).isEqualTo("银杏同学_01");

        assertThatThrownBy(() -> policy.normalizeUsername("bad name"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.USERNAME_INVALID));
    }

    /** 新密码允许纯数字、纯字母和符号，严格校验4–32位边界，不接受空格或中文。 */
    @Test
    void shouldValidatePasswordStrengthAndByteLimit() {
        policy.validatePassword("Ginkgo2026");
        for (String password : List.of("1234", "abcd", "!!!!", "a".repeat(32))) policy.validatePassword(password);
        assertThat(policy.normalizeUsername("Ka")).isEqualTo("Ka");
        assertThat(policy.normalizeUsername("ka")).isEqualTo("ka");
        assertThat(policy.normalizeUsername("中".repeat(24))).hasSize(24);

        for (String invalid : List.of("123", "a".repeat(33), " abcd", "中文密码", "abcd\n")) {
            assertThatThrownBy(() -> policy.validatePassword(invalid))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.PASSWORD_WEAK));
        }
        assertThatThrownBy(() -> policy.validatePassword("银".repeat(24) + "1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.PASSWORD_WEAK));
    }
}
