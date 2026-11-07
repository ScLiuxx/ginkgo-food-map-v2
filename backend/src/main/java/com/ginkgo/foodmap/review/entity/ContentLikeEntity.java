package com.ginkgo.foodmap.review.entity;

import lombok.Data;

import java.time.Instant;

/** 保留首次关系和当前状态；目标和用户为逻辑引用，不跟随源内容级联删除。 */
@Data
public class ContentLikeEntity {
    private Long id;
    private String targetType;
    private long targetId;
    private long userId;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}
