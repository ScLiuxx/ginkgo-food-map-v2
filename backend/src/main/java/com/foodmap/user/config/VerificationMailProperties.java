package com.foodmap.user.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 邮箱验证码邮件的非敏感展示配置。
 *
 * <p>SMTP 主机和凭据继续由 Spring Mail 管理；这里仅保存发件地址和主题前缀，避免 Service
 * 硬编码品牌文案。使用 Lombok {@link Data} 生成配置绑定所需访问器。</p>
 */
@Data
@Validated
@ConfigurationProperties("app.user.verification-mail")
public class VerificationMailProperties {

    @NotBlank
    @Email
    private String from;

    @NotBlank
    private String subjectPrefix;
}
