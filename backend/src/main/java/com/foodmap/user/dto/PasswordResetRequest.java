package com.foodmap.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.ToString;

/** 密码重置入参；只允许通过绑定邮箱的专用验证码修改密码，不接收用户 ID 或角色。 */
@Data
public class PasswordResetRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "[0-9]{6}", message = "验证码必须是6位数字")
    @ToString.Exclude
    private String verificationCode;

    /** 明文只在请求内用于校验与哈希，不参与对象日志输出，也不执行 trim。 */
    @NotBlank(message = "新密码不能为空")
    @ToString.Exclude
    private String newPassword;
}
