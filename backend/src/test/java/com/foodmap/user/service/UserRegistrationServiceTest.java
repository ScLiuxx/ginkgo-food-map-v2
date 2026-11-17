package com.foodmap.user.service;

import com.foodmap.user.policy.UserRegistrationPolicy;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.user.config.UserRegistrationProperties;
import com.foodmap.user.domain.VerificationCodePurpose;
import com.foodmap.user.dto.RegisterRequest;
import com.foodmap.user.entity.RoleEntity;
import com.foodmap.user.entity.UserEntity;
import com.foodmap.user.entity.UserRoleEntity;
import com.foodmap.user.exception.UserErrorCode;
import com.foodmap.user.exception.VerificationCodeErrorCode;
import com.foodmap.user.mapper.RoleMapper;
import com.foodmap.user.mapper.UserMapper;
import com.foodmap.user.mapper.UserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 用户注册编排测试，隔离真实 MySQL 和 Redis，验证调用顺序、失败短路及敏感数据处理。
 */
class UserRegistrationServiceTest {

    private UserMapper userMapper;
    private RoleMapper roleMapper;
    private UserRoleMapper userRoleMapper;
    private VerificationCodeService verificationCodeService;
    private PasswordEncoder passwordEncoder;
    private UserRegistrationService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        roleMapper = mock(RoleMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        verificationCodeService = mock(VerificationCodeService.class);
        passwordEncoder = mock(PasswordEncoder.class);

        UserRegistrationProperties properties = new UserRegistrationProperties();
        properties.setAllowedEmailDomains(List.of("std.uestc.edu.cn"));
        properties.setUsernameMinLength(3);
        properties.setUsernameMaxLength(32);
        properties.setPasswordMinLength(8);
        properties.setPasswordMaxBytes(72);

        service = new UserRegistrationService(
                userMapper,
                roleMapper,
                userRoleMapper,
                verificationCodeService,
                new UserRegistrationPolicy(properties),
                passwordEncoder
        );
    }

    /** 正常注册会消费验证码、保存 BCrypt 哈希并分配普通用户角色。 */
    @Test
    void shouldRegisterVerifiedUserWithDefaultRole() {
        RegisterRequest request = request();
        RoleEntity role = role(7L);
        when(roleMapper.selectByCode("ROLE_USER")).thenReturn(Optional.of(role));
        when(passwordEncoder.encode("Foodmap2026")).thenReturn("bcrypt-hash");
        when(userMapper.insert(any(UserEntity.class))).thenAnswer(invocation -> {
            invocation.<UserEntity>getArgument(0).setId(42L);
            return 1;
        });
        when(userRoleMapper.insert(any(UserRoleEntity.class))).thenReturn(1);

        service.register(request);

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(userCaptor.capture());
        UserEntity saved = userCaptor.getValue();
        assertThat(saved.getUsername()).isEqualTo("银杏同学_01");
        assertThat(saved.getEmail()).isEqualTo("student@std.uestc.edu.cn");
        assertThat(saved.getPasswordHash()).isEqualTo("bcrypt-hash").isNotEqualTo(request.getPassword());
        assertThat(saved.isEmailVerified()).isTrue();

        ArgumentCaptor<UserRoleEntity> relationCaptor = ArgumentCaptor.forClass(UserRoleEntity.class);
        verify(userRoleMapper).insert(relationCaptor.capture());
        assertThat(relationCaptor.getValue().getUserId()).isEqualTo(42L);
        assertThat(relationCaptor.getValue().getRoleId()).isEqualTo(7L);

        InOrder securityOrder = inOrder(verificationCodeService, passwordEncoder, userMapper);
        securityOrder.verify(verificationCodeService).consume(
                VerificationCodePurpose.REGISTER,
                "student@std.uestc.edu.cn",
                "123456"
        );
        securityOrder.verify(passwordEncoder).encode("Foodmap2026");
        securityOrder.verify(userMapper).insert(any(UserEntity.class));
    }

    /** 已注册邮箱在验证码消费前被拒绝，用户不需要重新获取验证码。 */
    @Test
    void shouldRejectDuplicateEmailBeforeConsumingCode() {
        when(userMapper.existsByEmail("student@std.uestc.edu.cn")).thenReturn(true);

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(UserErrorCode.EMAIL_ALREADY_REGISTERED));

        verifyNoInteractions(roleMapper, userRoleMapper, verificationCodeService, passwordEncoder);
        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    /** 验证码错误时不会计算密码哈希或写入数据库。 */
    @Test
    void shouldStopWhenVerificationCodeIsInvalid() {
        when(roleMapper.selectByCode("ROLE_USER")).thenReturn(Optional.of(role(7L)));
        org.mockito.Mockito.doThrow(new BusinessException(VerificationCodeErrorCode.INVALID_OR_EXPIRED))
                .when(verificationCodeService)
                .consume(VerificationCodePurpose.REGISTER, "student@std.uestc.edu.cn", "123456");

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(VerificationCodeErrorCode.INVALID_OR_EXPIRED));

        verifyNoInteractions(passwordEncoder, userRoleMapper);
        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    /** 数据库唯一约束捕获并发注册时返回稳定错误，不泄露约束名或 SQL。 */
    @Test
    void shouldMapConcurrentUniqueConstraintConflict() {
        when(roleMapper.selectByCode("ROLE_USER")).thenReturn(Optional.of(role(7L)));
        when(passwordEncoder.encode("Foodmap2026")).thenReturn("bcrypt-hash");
        when(userMapper.insert(any(UserEntity.class))).thenThrow(new DuplicateKeyException("test constraint"));

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> {
                            assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.REGISTRATION_CONFLICT);
                            assertThat(exception.getMessage()).doesNotContain("constraint");
                        });

        verifyNoInteractions(userRoleMapper);
    }

    private static RegisterRequest request() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(" Student@STD.UESTC.EDU.CN ");
        request.setVerificationCode("123456");
        request.setUsername(" 银杏同学_01 ");
        request.setPassword("Foodmap2026");
        return request;
    }

    private static RoleEntity role(long id) {
        RoleEntity role = RoleEntity.create("ROLE_USER", "普通用户");
        role.setId(id);
        return role;
    }
}
