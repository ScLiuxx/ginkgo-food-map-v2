package com.ginkgo.foodmap.user.service;

import com.ginkgo.foodmap.user.policy.UserRegistrationPolicy;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.user.config.UserRegistrationProperties;
import com.ginkgo.foodmap.user.domain.UserStatus;
import com.ginkgo.foodmap.user.domain.VerificationCodePurpose;
import com.ginkgo.foodmap.user.dto.PasswordResetRequest;
import com.ginkgo.foodmap.user.entity.UserEntity;
import com.ginkgo.foodmap.user.exception.UserErrorCode;
import com.ginkgo.foodmap.user.exception.VerificationCodeErrorCode;
import com.ginkgo.foodmap.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 密码重置编排测试：证明无有效验证码不能哈希或写库，数据库故障和并发冲突不会被当作成功。 */
class PasswordResetServiceTest {

    private static final String EMAIL = "student@std.uestc.edu.cn";
    private UserMapper users;
    private VerificationCodeService codes;
    private PasswordEncoder passwords;
    private PasswordResetService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        users = mock(UserMapper.class);
        codes = mock(VerificationCodeService.class);
        passwords = mock(PasswordEncoder.class);
        UserRegistrationProperties properties = new UserRegistrationProperties();
        properties.setAllowedEmailDomains(List.of("std.uestc.edu.cn"));
        properties.setPasswordMinLength(8);
        properties.setPasswordMaxBytes(72);
        service = new PasswordResetService(users, codes, new UserRegistrationPolicy(properties), passwords);
        user = UserEntity.registerVerified("ginkgo_user", EMAIL, "old-hash");
        user.setId(42L);
        user.setRowVersion(7L);
        user.setCredentialVersion(3L);
        when(users.selectByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwords.encode("NewPassword2026")).thenReturn("new-hash");
        when(users.resetPasswordIfCurrent(42L, EMAIL, 7L, 3L, "new-hash")).thenReturn(1);
    }

    /** 规范化邮箱但保留密码空格；先消费专用验证码，原子更新只接收哈希及读取时的版本。 */
    @Test
    void shouldConsumeCodeBeforeHashingAndUpdating() {
        service.resetPassword(request());
        var order = inOrder(codes, users, passwords);
        order.verify(codes).consume(VerificationCodePurpose.PASSWORD_RESET, EMAIL, "012345");
        order.verify(users).selectByEmail(EMAIL);
        order.verify(passwords).encode("NewPassword2026");
        order.verify(users).resetPasswordIfCurrent(42L, EMAIL, 7L, 3L, "new-hash");
        assertThat(request().toString()).doesNotContain("012345", "NewPassword2026");
    }

    /** 校园域名与密码强度均在服务端检查，失败不消费验证码或读取账号。 */
    @Test
    void shouldRejectInvalidInputBeforeConsumingCode() {
        PasswordResetRequest weak = request();
        weak.setNewPassword("123");
        assertThatThrownBy(() -> service.resetPassword(weak))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(UserErrorCode.PASSWORD_WEAK));
        PasswordResetRequest outsider = request();
        outsider.setEmail("student@example.com");
        assertThatThrownBy(() -> service.resetPassword(outsider))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(UserErrorCode.CAMPUS_EMAIL_REQUIRED));
        verifyNoInteractions(codes, users, passwords);
    }

    /** 无效、用途不符、耗尽或 Redis 故障均直接停止，不能探测账号或继续改密。 */
    @Test
    void shouldStopOnVerificationFailure() {
        for (var error : List.of(VerificationCodeErrorCode.INVALID_OR_EXPIRED,
                VerificationCodeErrorCode.ATTEMPTS_EXCEEDED, VerificationCodeErrorCode.SERVICE_UNAVAILABLE)) {
            doThrow(new BusinessException(error)).when(codes)
                    .consume(VerificationCodePurpose.PASSWORD_RESET, EMAIL, "012345");
            assertThatThrownBy(() -> service.resetPassword(request()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode()).isEqualTo(error));
        }
        verifyNoInteractions(users, passwords);
    }

    /** 即使测试替身允许验证码通过，未注册、停用、锁定、删除或未验证账号仍不能重置。 */
    @Test
    void shouldRejectUnavailableAccountsWithSameError() {
        when(users.selectByEmail(EMAIL)).thenReturn(Optional.empty());
        assertInvalidAccount();
        when(users.selectByEmail(EMAIL)).thenReturn(Optional.of(user));
        for (var state : List.of(UserStatus.DISABLED, UserStatus.LOCKED, UserStatus.DELETED)) {
            user.setStatus(state);
            assertInvalidAccount();
        }
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(false);
        assertInvalidAccount();
        verifyNoInteractions(passwords);
        verify(users, never()).resetPasswordIfCurrent(anyLong(), anyString(), anyLong(), anyLong(), anyString());
    }

    /** 乐观更新0行表示并发状态变化，不能宣告改密成功或覆盖别人的更新。 */
    @Test
    void shouldReportConcurrentChange() {
        when(users.resetPasswordIfCurrent(42L, EMAIL, 7L, 3L, "new-hash")).thenReturn(0);
        assertThatThrownBy(() -> service.resetPassword(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(UserErrorCode.PASSWORD_RESET_CONFLICT));
    }

    /** 读取或更新故障统一503，不让数据库异常中的 SQL、邮箱、哈希进入用户提示。 */
    @Test
    void shouldHideDatabaseFailureDetails() {
        when(users.resetPasswordIfCurrent(42L, EMAIL, 7L, 3L, "new-hash"))
                .thenThrow(new DataAccessResourceFailureException("private SQL new-hash"));
        assertUnavailable();
        when(users.selectByEmail(EMAIL)).thenThrow(new DataAccessResourceFailureException("private SQL"));
        assertUnavailable();
    }

    /** 数据库排序规则可能返回重音相似邮箱，必须精确匹配绑定邮箱才可改密。 */
    @Test
    void shouldRejectDifferentMailboxReturnedByDatabaseCollation() {
        user.setEmail("stúdent@std.uestc.edu.cn");
        assertInvalidAccount();
        verifyNoInteractions(passwords);
        verify(users, never()).resetPasswordIfCurrent(anyLong(), anyString(), anyLong(), anyLong(), anyString());
    }

    private void assertInvalidAccount() {
        assertThatThrownBy(() -> service.resetPassword(request()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(VerificationCodeErrorCode.INVALID_OR_EXPIRED));
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> service.resetPassword(request()))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(UserErrorCode.PASSWORD_RESET_UNAVAILABLE);
                    assertThat(failure.getMessage()).doesNotContain("private SQL", "new-hash");
                });
    }

    private static PasswordResetRequest request() {
        PasswordResetRequest request = new PasswordResetRequest();
        request.setEmail(" Student@STD.UESTC.EDU.CN ");
        request.setVerificationCode("012345");
        request.setNewPassword("NewPassword2026");
        return request;
    }
}
