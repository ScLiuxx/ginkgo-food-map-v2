package com.foodmap.user.controller;

import com.foodmap.common.api.ApiResponse;
import com.foodmap.user.dto.RegisterRequest;
import com.foodmap.user.service.UserRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户认证流程的公开 HTTP 入口。
 *
 * <p>Controller 只负责请求校验、HTTP 状态和统一响应，注册规则、密码处理与数据库操作全部交给
 * Service。后续登录、退出和密码重置接口可继续放在该认证入口下。</p>
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRegistrationService userRegistrationService;

    /**
     * 使用校园邮箱验证码创建账号。注册成功只返回结果，不自动登录或签发 JWT。
     *
     * @param request 注册输入
     * @return HTTP 201 和统一成功响应
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Void>> register(@Valid @RequestBody RegisterRequest request) {
        userRegistrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(null));
    }
}
