package com.foodmap.user.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 邮箱验证码用途。
 *
 * <p>用途同时进入 Redis 键和 HMAC 原文，注册验证码不能用于密码重置，反之亦然。</p>
 */
@Getter
@RequiredArgsConstructor
public enum VerificationCodePurpose {
    REGISTER("register"),
    PASSWORD_RESET("password-reset");

    private final String keySegment;
}
