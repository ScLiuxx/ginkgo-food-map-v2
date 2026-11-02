package com.ginkgo.foodmap.user.service;

import com.ginkgo.foodmap.user.policy.UserRegistrationPolicy;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.user.domain.VerificationCodePurpose;
import com.ginkgo.foodmap.user.dto.PasswordResetRequest;
import com.ginkgo.foodmap.user.exception.UserErrorCode;
import com.ginkgo.foodmap.user.exception.VerificationCodeErrorCode;
import com.ginkgo.foodmap.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 通过邮箱所有权证明重置密码；仅访问 user 模块，不依赖认证模块的会话存储。 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserMapper userMapper;
    private final VerificationCodeService verificationCodeService;
    private final UserRegistrationPolicy registrationPolicy;
    private final PasswordEncoder passwordEncoder;

    /**
     * 消费密码重置验证码，原子更新密码哈希并递增凭证版本，不自动登录。
     *
     * <p>先检查输入，再消费专用验证码，最后才查询账号和计算 BCrypt，避免无邮箱所有权证明的
     * 请求探测账号或消耗昂贵哈希资源。不存在、停用或未验证账号均复用验证码无效提示，不恢复账号。
     * 更新同时核对邮箱、行版本、凭证版本与可登录状态，防止计算哈希期间的账号变化被覆盖。
     * 数据库提交后，认证链读取到新凭证版本即拒绝所有旧令牌；Redis 会话记录按原 TTL 回收。
     * Redis 消费与数据库事务无法原子提交，后续冲突或故障需要重新获取验证码，不恢复已消费验证码。</p>
     *
     * @param request 已通过 HTTP 基础校验的输入，密码不做trim，含空格时由统一策略拒绝
     */
    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        resetPasswordAndGetUsername(request);
    }

    /** 返回已经通过邮箱验证且成功改密的用户名，仅供登录页预填，不建立新会话。 */
    @Transactional
    public String resetPasswordAndGetUsername(PasswordResetRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String email = registrationPolicy.normalizeCampusEmail(request.getEmail());
        registrationPolicy.validatePassword(request.getNewPassword());
        verificationCodeService.consume(VerificationCodePurpose.PASSWORD_RESET, email, request.getVerificationCode());

        try {
            var user = userMapper.selectByEmail(email)
                    // MySQL 邮箱索引使用重音不敏感排序规则；必须精确比较规范化邮箱，
                    // 避免相似但不同的邮箱凭验证码修改到另一账号。
                    .filter(candidate -> email.equals(candidate.getEmail())
                            && candidate.isLoginAllowed() && candidate.isEmailVerified())
                    .orElseThrow(() -> new BusinessException(VerificationCodeErrorCode.INVALID_OR_EXPIRED));
            String passwordHash = passwordEncoder.encode(request.getNewPassword());
            if (userMapper.resetPasswordIfCurrent(user.getId(), email, user.getRowVersion(),
                    user.getCredentialVersion(), passwordHash) != 1) {
                throw new BusinessException(UserErrorCode.PASSWORD_RESET_CONFLICT);
            }
            return user.getUsername();
        } catch (DataAccessException exception) {
            // 不记录包含 SQL 参数的底层异常，避免邮箱和密码哈希被日志或响应泄露。
            throw new BusinessException(UserErrorCode.PASSWORD_RESET_UNAVAILABLE);
        }
    }
}
