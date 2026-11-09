package com.ginkgo.foodmap.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/** username字段兼容用户名或注册邮箱，密码保持原样；密码不参与日志输出，也不执行 trim。 */
@Data
public class LoginRequest {
    @NotBlank(message = "请输入用户名或邮箱")
    @Size(max = 254, message = "用户名或邮箱过长")
    private String username;

    @NotBlank(message = "请输入密码")
    @Size(max = 72, message = "密码过长")
    @ToString.Exclude
    private String password;
}
