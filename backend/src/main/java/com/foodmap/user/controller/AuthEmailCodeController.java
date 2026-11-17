package com.foodmap.user.controller;

import com.foodmap.common.api.ApiResponse;
import com.foodmap.user.dto.EmailCodeRequest;
import com.foodmap.user.service.EmailCodeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册和密码重置验证码的公开 HTTP 接口。
 *
 * <p>Controller 只负责协议转换和参数校验，不读取或接收客户端 IP。当前防刷策略只按校园邮箱执行，
 * 后续确有攻击流量时再在网关或 Nginx 增加来源级限流。</p>
 */
@RestController
@RequestMapping("/auth/email/code")
@RequiredArgsConstructor
public class AuthEmailCodeController {

    private final EmailCodeService emailCodeService;

    /** 发送校园邮箱注册验证码。 */
    @PostMapping("/register")
    public ApiResponse<Void> sendRegisterCode(@Valid @RequestBody EmailCodeRequest request) {
        emailCodeService.sendRegistrationCode(request.getEmail());
        return ApiResponse.ok(null);
    }

    /** 发送密码重置验证码；响应不表示邮箱是否已经注册。 */
    @PostMapping("/password-reset")
    public ApiResponse<Void> sendPasswordResetCode(@Valid @RequestBody EmailCodeRequest request) {
        emailCodeService.sendPasswordResetCode(request.getEmail());
        return ApiResponse.ok(null);
    }
}
