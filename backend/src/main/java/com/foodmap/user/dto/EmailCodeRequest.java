package com.foodmap.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发送邮箱验证码的请求 DTO。
 *
 * <p>这里只验证基础邮箱格式；允许注册的校园域名由 Service 根据配置进行精确校验，不能依赖前端。</p>
 */
@Data
public class EmailCodeRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;
}
