package com.foodmap.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;

import lombok.Data;

/** 已有相册关联或本次临时文件二选一，列表顺序决定封面与展示顺序。 */
@Data
public class RestaurantPhotoSelection {
    private String photoId;
    private String fileId;

    @JsonIgnore
    @AssertTrue(message = "照片必须指定已有相册ID或本次文件ID")
    public boolean isSingleSource() {
        return (photoId == null) != (fileId == null);
    }

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("照片包含未支持的字段");
    }
}
