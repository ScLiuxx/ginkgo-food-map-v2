package com.foodmap.user.service;

import com.foodmap.user.policy.UserRegistrationPolicy;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.user.domain.VerificationCodePurpose;
import com.foodmap.user.exception.UserErrorCode;
import com.foodmap.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 邮箱验证码接口的用户业务服务，负责校园域名、账号存在性和用途规则。
 *
 * <p>Controller 不直接查询 Mapper，也不自行决定验证码用途。密码重置接口对未注册邮箱静默返回，
 * 使响应内容和状态与已注册邮箱保持一致，避免直接暴露账号是否存在。</p>
 */
@Service
@RequiredArgsConstructor
public class EmailCodeService {

    private final UserMapper userMapper;
    private final VerificationCodeService verificationCodeService;
    private final UserRegistrationPolicy registrationPolicy;

    /** 发送注册验证码；已注册邮箱返回明确冲突，最终注册仍依赖数据库唯一约束。 */
    public void sendRegistrationCode(String email) {
        String normalizedEmail = registrationPolicy.normalizeCampusEmail(email);
        if (userMapper.existsByEmail(normalizedEmail)) {
            throw new BusinessException(UserErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        verificationCodeService.send(VerificationCodePurpose.REGISTER, normalizedEmail);
    }

    /**
     * 发送密码重置验证码。未注册邮箱不发送邮件，但调用方仍得到相同成功响应。
     */
    public void sendPasswordResetCode(String email) {
        String normalizedEmail = registrationPolicy.normalizeCampusEmail(email);
        if (userMapper.existsByEmail(normalizedEmail)) {
            verificationCodeService.send(VerificationCodePurpose.PASSWORD_RESET, normalizedEmail);
        }
    }

}
