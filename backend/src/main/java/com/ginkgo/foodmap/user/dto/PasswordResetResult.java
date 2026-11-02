package com.ginkgo.foodmap.user.dto;

import lombok.Data;

/** 仅在验证码验证且密码提交成功后返回登录用户名；不包含密码或令牌。 */
@Data
public class PasswordResetResult {
    private String username;
}
