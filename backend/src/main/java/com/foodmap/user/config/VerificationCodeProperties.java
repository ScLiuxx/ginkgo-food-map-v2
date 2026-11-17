package com.foodmap.user.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * user 模块拥有的邮箱验证码策略配置。
 *
 * <p>使用 Lombok {@link Data} 生成 Spring Boot 配置绑定需要的访问器，不手写 Getter、Setter
 * 或构造器。HMAC 密钥只能通过安全配置注入，不得进入日志或版本库。</p>
 */
@Data
@Validated
@ConfigurationProperties("app.user.verification-code")
public class VerificationCodeProperties {

    @Min(6)
    @Max(8)
    private int length;

    @NotNull
    private Duration timeToLive;

    @NotNull
    private Duration resendCooldown;

    @Min(1)
    private int maxAttempts;

    @Min(1)
    private int emailHourlyLimit;

    @Min(1)
    private int emailDailyLimit;

    @NotBlank
    @Size(min = 32)
    @ToString.Exclude
    private String hmacSecret;

    @NotBlank
    private String keyPrefix;
}
