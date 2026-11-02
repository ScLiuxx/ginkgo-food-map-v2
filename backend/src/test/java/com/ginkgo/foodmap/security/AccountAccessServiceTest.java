package com.ginkgo.foodmap.security;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.security.dto.LoginRequest;
import com.ginkgo.foodmap.security.exception.AuthenticationErrorCode;
import com.ginkgo.foodmap.security.service.AccountAccessService;
import com.ginkgo.foodmap.security.service.LoginService;
import com.ginkgo.foodmap.security.vo.LoginResponse;
import com.ginkgo.foodmap.user.dto.RegisterRequest;
import com.ginkgo.foodmap.user.service.UserRegistrationService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

/** 自动登录必须发生在user服务成功返回之后；失败不能把账号已提交的结果隐瞒为注册/重置失败。 */
class AccountAccessServiceTest {
    private final UserRegistrationService registrations = mock(UserRegistrationService.class);
    private final LoginService login = mock(LoginService.class);
    private final AccountAccessService access = new AccountAccessService(registrations, login);

    @Test void registrationCommitsBeforeLoginAndKeepsUsernameCase() {
        RegisterRequest request = new RegisterRequest(); request.setUsername(" Ka "); request.setPassword("1234");
        LoginResponse session = mock(LoginResponse.class);
        when(login.login(any())).thenReturn(session);
        assertThat(access.register(request)).isSameAs(session);
        var order = inOrder(registrations, login);
        order.verify(registrations).register(request);
        order.verify(login).login(argThat(r -> r.getUsername().equals("Ka") && r.getPassword().equals("1234")));
    }

    @Test void invalidVerificationNeverCreatesASession() {
        RegisterRequest request = new RegisterRequest();
        doThrow(new BusinessException(AuthenticationErrorCode.INVALID_CREDENTIALS)).when(registrations).register(request);
        assertThatThrownBy(() -> access.register(request)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(login);
    }

    @Test void loginFailureReportsTheAlreadyCompletedOperation() {
        RegisterRequest request = new RegisterRequest(); request.setUsername("Ka"); request.setPassword("1234");
        when(login.login(any(LoginRequest.class))).thenThrow(new BusinessException(AuthenticationErrorCode.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> access.register(request)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(AuthenticationErrorCode.REGISTER_LOGIN_FAILED));
    }
}
