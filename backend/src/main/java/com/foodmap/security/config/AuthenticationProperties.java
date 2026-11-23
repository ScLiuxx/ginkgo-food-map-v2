package com.foodmap.security.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.hibernate.validator.constraints.time.DurationMin;
import org.hibernate.validator.constraints.time.DurationMax;

import java.time.Duration;

/**
 * 认证安全策略的类型安全配置，由 {@code security} 模块独立拥有。
 *
 * <p>JWT 声明和登录失败保护集中在此处，避免在 Service 中出现魔法数字。
 * RSA 私钥只接收配置位置，禁止写入源码和版本库。邮箱验证码策略归 user 模块管理。</p>
 *
 */
@Data
@Validated
@ConfigurationProperties("app.security.authentication")
public class AuthenticationProperties {

    @Valid
    @NotNull
    private Jwt jwt;

    @Valid
    @NotNull
    private LoginProtection loginProtection;

    /**
     * 单 JWT 的签发与验证策略。
     *
     */
    @Data
    public static class Jwt {

        @NotBlank
        private String issuer;

        @NotBlank
        private String audience;

        @NotNull
        @DurationMin(seconds = 60)
        @DurationMax(days = 30)
        private Duration timeToLive;

        @NotBlank
        private String privateKeyLocation;

        @NotBlank
        private String publicKeyLocation;
    }

    /**
     * 密码登录暴力破解防护策略。
     *
     */
    @Data
    public static class LoginProtection {

        @Min(1)
        private int maxFailedAttempts;

        @NotNull
        @DurationMin(seconds = 1)
        private Duration failureWindow;

        @NotNull
        @DurationMin(seconds = 1)
        private Duration lockDuration;
    }
}
