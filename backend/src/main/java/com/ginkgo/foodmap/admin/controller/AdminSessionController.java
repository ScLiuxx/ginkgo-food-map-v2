package com.ginkgo.foodmap.admin.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.security.service.LoginService;
import com.ginkgo.foodmap.security.vo.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理工作台的会话校验入口；复用认证公开服务，不直接读取用户表或信任客户端角色。 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminSessionController {
    private final LoginService loginService;

    /** JWT、Redis会话及数据库当前角色全部有效后返回公开身份，供工作台挂载前校验；响应禁止缓存。 */
    @GetMapping("/me")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurrentUser>> me(@AuthenticationPrincipal Jwt jwt) {
        var user = loginService.currentUser(Long.parseLong(jwt.getSubject()), jwt.getClaim("credential_version"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(user));
    }
}
