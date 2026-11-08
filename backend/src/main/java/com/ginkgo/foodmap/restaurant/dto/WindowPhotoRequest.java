package com.ginkgo.foodmap.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import lombok.Data;

/** 仅接收上传回执ID，不接受OSS路径、上传者或封面字段。 */
@Data
public class WindowPhotoRequest {
    @NotBlank
    @Pattern(regexp = "[1-9][0-9]{0,18}")
    private String fileId;

    /** 拒绝未知字段，避免客户端误以为可覆盖图片归属。 */
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("窗口图片包含不支持的字段");
    }
}
