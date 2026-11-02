package com.ginkgo.foodmap.security.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.security.dto.LoginRequest;
import com.ginkgo.foodmap.security.exception.AuthenticationErrorCode;
import com.ginkgo.foodmap.security.vo.LoginResponse;
import com.ginkgo.foodmap.user.dto.RegisterRequest;
import com.ginkgo.foodmap.user.service.UserRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** security只调用user公开服务：先提交账号事务，再复用真实密码验证、限流和Redis会话签发。 */
@Service
@RequiredArgsConstructor
public class AccountAccessService {
    private final UserRegistrationService registrations;
    private final LoginService login;

    /** 不能把会话签发放在账号事务内，避免返回令牌后数据库提交失败。 */
    public LoginResponse register(RegisterRequest request) {
        registrations.register(request);
        return establish(request.getUsername().trim(), request.getPassword(), AuthenticationErrorCode.REGISTER_LOGIN_FAILED);
    }

    private LoginResponse establish(String username, String password, AuthenticationErrorCode failure) {
        LoginRequest credentials = new LoginRequest();
        credentials.setUsername(username);
        credentials.setPassword(password);
        try {
            return login.login(credentials);
        } catch (BusinessException exception) {
            // 账号操作已提交，响应必须明确区分“操作失败”和“自动登录失败”，不让用户重复消耗验证码。
            throw new BusinessException(failure);
        }
    }
}
