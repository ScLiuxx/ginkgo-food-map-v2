package com.ginkgo.foodmap.review.dto;

import lombok.Data;
import lombok.ToString;

/**
 * 跨模块公开的点评/回复目标快照。
 *
 * <p>只暴露举报和治理需要的来源事实，不携带客户端请求标识或评分等无关字段；正文仅用于
 * 服务端保存内容快照，不由普通读取接口返回。
 */
@Data
public class ContentTargetSnapshot {
    private String targetType;
    private long targetId;
    private long reviewId;
    private long authorUserId;
    @ToString.Exclude private String body;
    private boolean windowTarget;
}
