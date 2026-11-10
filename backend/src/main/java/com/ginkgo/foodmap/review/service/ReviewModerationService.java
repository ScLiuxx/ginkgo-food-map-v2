package com.ginkgo.foodmap.review.service;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * 面向moderation模块的违规内容处置入口。
 *
 * <p>只按目标类型分发到既有删除服务，不复制父锁、回执和评分重算逻辑；跨模块调用不暴露
 * Mapper或Entity。删除是永久的，与作者自删共享同一套事务和重试语义。
 */
@Service
@RequiredArgsConstructor
public class ReviewModerationService {
    private final ReviewDeleteService reviews;
    private final ReplyDeleteService replies;

    /** 永久删除违规点评或回复；返回false表示此前已删除，调用方仍可继续结案。 */
    @PreAuthorize("hasRole('ADMIN')")
    public boolean remove(String targetType, long targetId) {
        if ("REVIEW".equals(targetType)) {
            return reviews.removeForModeration(targetId);
        }
        if ("REPLY".equals(targetType)) {
            return replies.removeForModeration(targetId);
        }
        throw new IllegalStateException("Unsupported moderation target: " + targetType);
    }
}
