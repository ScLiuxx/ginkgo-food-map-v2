package com.ginkgo.foodmap.user.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.user.dto.PasswordResetRequest;
import com.ginkgo.foodmap.user.dto.PasswordResetResult;
import com.ginkgo.foodmap.user.service.PasswordResetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公开密码重置入口；邮箱验证码的业务校验和密码写入均由 user 模块服务负责。 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    /** 重置成功仅返回已验证账号的用户名用于预填；旧会话失效，不签发新令牌。 */
    @PostMapping("/password-reset")
    public ResponseEntity<ApiResponse<PasswordResetResult>> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        PasswordResetResult result = new PasswordResetResult();
        result.setUsername(passwordResetService.resetPasswordAndGetUsername(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }
}
