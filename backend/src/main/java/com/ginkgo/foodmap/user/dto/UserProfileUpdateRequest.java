package com.ginkgo.foodmap.user.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.ToString;

/** 个人资料更新只接受bio；账号归属和不可修改字段不能通过JSON传入。 */
@Data
public class UserProfileUpdateRequest {
    @NotNull(message = "请提供个人简介，清空时传空字符串")
    @ToString.Exclude
    private String bio;

    /** 拒绝把数字、布尔值或数组静默转换为个人简介。 */
    @JsonSetter("bio")
    public void readBio(Object value) {
        if (value != null && !(value instanceof String)) throw new IllegalArgumentException("个人简介必须为字符串");
        bio = (String) value;
    }

    /** 未知字段不静默丢弃，避免客户端误以为用户名、邮箱或其他用户资料已修改。 */
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("个人资料请求包含不支持的字段");
    }
}
