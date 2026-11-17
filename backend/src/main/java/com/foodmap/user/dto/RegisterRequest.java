package com.foodmap.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.ToString;

/**
 * 用户注册接口入参。
 *
 * <p>注解只完成空值、邮箱形状和验证码形状等基础检查。校园邮箱域名、用户名字符范围、
 * 密码强度等可配置业务规则仍由 Service 校验，不能只依赖前端。验证码和密码被排除在
 * {@code toString()} 之外，避免请求对象进入日志时泄露敏感信息。</p>
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "[0-9]{6}", message = "验证码必须是6位数字")
    @ToString.Exclude
    private String verificationCode;

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    @ToString.Exclude
    private String password;
}
