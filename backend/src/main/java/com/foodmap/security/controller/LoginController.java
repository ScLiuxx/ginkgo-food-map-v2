package com.foodmap.security.controller;

import com.foodmap.common.api.ApiResponse;
import com.foodmap.security.dto.LoginRequest;
import com.foodmap.security.service.LoginService;
import com.foodmap.security.vo.CurrentUser;
import com.foodmap.security.vo.LoginResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 登录、当前账号及退出入口；含凭证或个人信息的响应均禁止缓存。 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class LoginController {
    private final LoginService loginService;

    /** 公开的用户名密码登录，成功返回 Bearer 凭证。 */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(loginService.login(request)));
    }

    /** 通过完整安全过滤链后读取当前用户，供刷新页面后恢复展示状态。 */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<CurrentUser>> me(@AuthenticationPrincipal Jwt jwt) {
        var user = loginService.currentUser(Long.parseLong(jwt.getSubject()), jwt.getClaim("credential_version"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(user));
    }

    /** 仅撤销当前会话；删除失败返回503，客户端不能把本地清除误认为服务端退出成功。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        loginService.logout(jwt.getId());
        return ApiResponse.ok(null);
    }
}
