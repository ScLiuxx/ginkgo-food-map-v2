package com.foodmap.security.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;
import java.time.Instant;

/** 登录成功返回短生命周期请求凭证；禁止将整个响应写入日志。 */
@Data
@AllArgsConstructor
public class LoginResponse {
    @ToString.Exclude
    private String accessToken;
    private String tokenType;
    private Instant expiresAt;
    private CurrentUser user;
}
