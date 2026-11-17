package com.foodmap.user.service;

import com.foodmap.user.policy.UserRegistrationPolicy;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.user.config.UserRegistrationProperties;
import com.foodmap.user.domain.VerificationCodePurpose;
import com.foodmap.user.exception.UserErrorCode;
import com.foodmap.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 邮箱验证码业务规则测试，隔离 MySQL、Redis 和 SMTP。
 */
class EmailCodeServiceTest {

    private UserMapper userMapper;
    private VerificationCodeService verificationCodeService;
    private EmailCodeService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        verificationCodeService = mock(VerificationCodeService.class);
        UserRegistrationProperties properties = new UserRegistrationProperties();
        properties.setAllowedEmailDomains(List.of("std.uestc.edu.cn"));
        service = new EmailCodeService(
                userMapper,
                verificationCodeService,
                new UserRegistrationPolicy(properties)
        );
    }

    /** 验证邮箱会规范化后再查询数据库并发送注册验证码。 */
    @Test
    void shouldNormalizeAndSendRegistrationCode() {
        service.sendRegistrationCode(" Student@STD.UESTC.EDU.CN ");

        verify(userMapper).existsByEmail("student@std.uestc.edu.cn");
        verify(verificationCodeService).send(
                VerificationCodePurpose.REGISTER,
                "student@std.uestc.edu.cn"
        );
    }

    /** 验证已注册邮箱不能继续获取注册验证码。 */
    @Test
    void shouldRejectRegisteredEmailForRegistration() {
        when(userMapper.existsByEmail("student@std.uestc.edu.cn")).thenReturn(true);

        assertThatThrownBy(() -> service.sendRegistrationCode(
                "student@std.uestc.edu.cn"
        )).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.EMAIL_ALREADY_REGISTERED));

        verify(verificationCodeService, never()).send(
                VerificationCodePurpose.REGISTER,
                "student@std.uestc.edu.cn"
        );
    }

    /** 验证非校园域名在 Service 层被拒绝，不能仅依赖 Controller。 */
    @Test
    void shouldRejectUnapprovedEmailDomain() {
        assertThatThrownBy(() -> service.sendRegistrationCode("student@example.com"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.CAMPUS_EMAIL_REQUIRED));

        verifyNoInteractions(userMapper, verificationCodeService);
    }

    /** 验证未注册邮箱请求密码重置时静默成功且不会发送邮件。 */
    @Test
    void shouldHideUnregisteredEmailDuringPasswordReset() {
        service.sendPasswordResetCode("unknown@std.uestc.edu.cn");

        verify(userMapper).existsByEmail("unknown@std.uestc.edu.cn");
        verifyNoInteractions(verificationCodeService);
    }

    /** 验证已注册邮箱可以获取用途隔离的密码重置验证码。 */
    @Test
    void shouldSendPasswordResetCodeForRegisteredEmail() {
        when(userMapper.existsByEmail("student@std.uestc.edu.cn")).thenReturn(true);

        service.sendPasswordResetCode("student@std.uestc.edu.cn");

        verify(verificationCodeService).send(
                VerificationCodePurpose.PASSWORD_RESET,
                "student@std.uestc.edu.cn"
        );
    }
}
