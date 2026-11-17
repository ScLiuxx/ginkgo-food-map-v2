package com.foodmap.user.service;

import com.foodmap.user.policy.UserRegistrationPolicy;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.user.domain.VerificationCodePurpose;
import com.foodmap.user.dto.RegisterRequest;
import com.foodmap.user.entity.RoleEntity;
import com.foodmap.user.entity.UserEntity;
import com.foodmap.user.entity.UserRoleEntity;
import com.foodmap.user.exception.UserErrorCode;
import com.foodmap.user.mapper.RoleMapper;
import com.foodmap.user.mapper.UserMapper;
import com.foodmap.user.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 完成校园用户注册的应用服务。
 *
 * <p>该服务负责协调输入策略、Redis 验证码、BCrypt 和三个用户持久化 Mapper。Controller 不参与
 * 注册决策，Entity 也不会暴露到接口层。数据库唯一约束负责处理并发注册的最终冲突。</p>
 */
@Service
@RequiredArgsConstructor
public class UserRegistrationService {

    private static final String DEFAULT_ROLE_CODE = "ROLE_USER";

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final VerificationCodeService verificationCodeService;
    private final UserRegistrationPolicy registrationPolicy;
    private final PasswordEncoder passwordEncoder;

    /**
     * 创建已验证邮箱的普通用户，但不建立登录会话。
     *
     * <p>先用友好查询拦截常见重复输入，数据库唯一约束再处理并发竞态。验证码在 BCrypt 计算和
     * 数据库写入前一次性消费，避免无有效验证码的请求消耗昂贵的密码哈希资源。Redis 与 MySQL
     * 无法加入同一个本地事务：验证码消费后的数据库故障会回滚数据库写入，但用户需要重新获取
     * 验证码，不尝试恢复已经消费的安全凭证。</p>
     *
     * @param request 已通过 Controller 基础校验的注册请求
     */
    @Transactional
    public void register(RegisterRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String email = registrationPolicy.normalizeCampusEmail(request.getEmail());
        String username = registrationPolicy.normalizeUsername(request.getUsername());
        registrationPolicy.validatePassword(request.getPassword());

        if (userMapper.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        if (userMapper.existsByUsername(username)) {
            throw new BusinessException(UserErrorCode.USERNAME_ALREADY_REGISTERED);
        }

        RoleEntity defaultRole = roleMapper.selectByCode(DEFAULT_ROLE_CODE)
                .orElseThrow(() -> new IllegalStateException("Default user role is not initialized"));

        verificationCodeService.consume(
                VerificationCodePurpose.REGISTER,
                email,
                request.getVerificationCode()
        );

        String passwordHash = passwordEncoder.encode(request.getPassword());
        UserEntity user = UserEntity.registerVerified(username, email, passwordHash);
        try {
            if (userMapper.insert(user) != 1 || user.getId() == null) {
                throw new IllegalStateException("User insert did not return a generated identifier");
            }
        } catch (DuplicateKeyException exception) {
            // 不解析数据库异常文本，避免依赖数据库厂商消息并防止底层信息进入 API。
            throw new BusinessException(UserErrorCode.REGISTRATION_CONFLICT);
        }

        if (userRoleMapper.insert(UserRoleEntity.assign(user.getId(), defaultRole.getId())) != 1) {
            throw new IllegalStateException("Default user role assignment failed");
        }
    }
}
