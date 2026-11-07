package com.ginkgo.foodmap.user.dto;

import com.fasterxml.jackson.annotation.*;

import jakarta.validation.constraints.NotNull;

import lombok.Data;

/** 隐私更新仅允许显式布尔值，拒绝账号ID、字符串及未知字段。 */
@Data
public class FavoriteVisibilityRequest {
    @NotNull private Boolean favoritesPublic;

    @JsonSetter("favoritesPublic")
    public void readPublic(Object value) {
        if (value != null && !(value instanceof Boolean)) {
            throw new IllegalArgumentException("Expected boolean privacy setting");
        }
        favoritesPublic = (Boolean) value;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown privacy field");
    }
}
