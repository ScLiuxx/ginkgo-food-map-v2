package com.ginkgo.foodmap.moderation.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

/** 删除违规内容的可选备注；空白说明在服务层规范化后按未填写处理。 */
@Data
public class ContentReportRemoveRequest {
    @Size(max = 200)
    private String note;
}
