package com.ginkgo.foodmap.security.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.security.service.AccountAccessService;
import com.ginkgo.foodmap.security.vo.LoginResponse;
import com.ginkgo.foodmap.user.dto.RegisterRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 新增注册自动登录入口；原/register不变，密码重置由user控制器处理且不自动登录。 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AccountAccessController {
    private final AccountAccessService access;

    /** 邮箱验证注册成功后返回与普通登录相同的凭证结构，禁止浏览器和代理缓存。 */
    @PostMapping("/register-and-login")
    public ResponseEntity<ApiResponse<LoginResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(access.register(request)));
    }

}
