package com.ginkgo.foodmap.moderation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;
import lombok.ToString;

/** 举报只接收原因和选填说明；举报人和被举报内容由路径及认证上下文确定。 */
@Data
public class ContentReportRequest {
    private String reason;
    @ToString.Exclude private String detail;

    /** 拒绝被静默忽略的未知字段，避免客户端把目标编号或作者编号误当作可写属性。 */
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("举报请求包含不支持的字段");
    }
}
