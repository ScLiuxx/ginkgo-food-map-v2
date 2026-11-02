package com.ginkgo.foodmap.user.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * 用户注册输入策略，由 {@code user} 模块拥有。
 *
 * <p>允许的校园邮箱域名和账号字段长度通过配置管理，便于未来增加其他学校域名，
 * 同时避免注册业务依赖 {@code security} 模块的内部实现。</p>
 *
 */
@Data
@Validated
@ConfigurationProperties("app.user.registration")
public class UserRegistrationProperties {

    @NotEmpty
    private List<String> allowedEmailDomains;

    @Min(2)
    @Max(64)
    private int usernameMinLength;

    @Min(2)
    @Max(64)
    private int usernameMaxLength;

    @Min(4)
    @Max(64)
    private int passwordMinLength;

    /** 新注册/重置密码最长32个ASCII字符；历史登录密码不受新规则追溯限制。 */
    @Min(4)
    @Max(32)
    private int passwordMaxLength = 32;

    @Min(16)
    @Max(72)
    private int passwordMaxBytes;
}
