package com.foodmap.user.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 改名只接收新用户名，账号归属由当前会话决定。 */
@Data
public class UsernameUpdateRequest {
    @NotBlank(message = "请输入用户名")
    private String username;

    @JsonSetter("username")
    public void readUsername(Object value) {
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("用户名必须为字符串");
        }
        username = (String) value;
    }

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("用户名修改请求包含不支持的字段");
    }
}
