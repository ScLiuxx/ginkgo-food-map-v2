package com.ginkgo.foodmap.review.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Data;
import lombok.ToString;

/** 回复仅接收正文和幂等编号，作者由认证上下文确定。 */
@Data
public class ReplyPublishRequest {
    private String clientRequestId;
    @ToString.Exclude private String body;

    /** 防止作者或嵌套回复等未知字段被默认JSON配置静默忽略。 */
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("回复请求包含不支持的字段");
    }
}
