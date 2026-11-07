package com.ginkgo.foodmap.user.dto;

import com.fasterxml.jackson.annotation.*;

import jakarta.validation.constraints.NotNull;

import lombok.Data;

/** 同时显式提交两个开关，拒绝身份字段及布尔类型强制转换。 */
@Data
public class FollowPrivacyRequest {
    @NotNull private Boolean followingPublic;
    @NotNull private Boolean followersPublic;

    @JsonSetter("followingPublic")
    public void readFollowing(Object value) {
        followingPublic = bool(value);
    }

    @JsonSetter("followersPublic")
    public void readFollowers(Object value) {
        followersPublic = bool(value);
    }

    @JsonAnySetter
    public void reject(String name, Object value) {
        throw new IllegalArgumentException("Unknown follow privacy field");
    }

    private Boolean bool(Object value) {
        if (value != null && !(value instanceof Boolean)) {
            throw new IllegalArgumentException("Expected boolean");
        }
        return (Boolean) value;
    }
}
