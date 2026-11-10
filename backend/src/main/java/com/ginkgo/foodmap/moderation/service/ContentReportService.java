package com.ginkgo.foodmap.moderation.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.moderation.dto.ContentReportRequest;
import com.ginkgo.foodmap.moderation.entity.ContentReportEntity;
import com.ginkgo.foodmap.moderation.exception.ModerationErrorCode;
import com.ginkgo.foodmap.moderation.mapper.ContentReportMapper;
import com.ginkgo.foodmap.moderation.policy.ContentReportPolicy;
import com.ginkgo.foodmap.moderation.vo.ContentReportViews;
import com.ginkgo.foodmap.review.service.ContentTargetService;
import com.ginkgo.foodmap.security.service.CurrentActorService;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 举报提交应用服务。
 *
 * <p>被举报内容在核查前继续公开（RISK-003）；提交时只保存正文快照，不改变内容可见性、评分
 * 或作者状态。同一用户对同一内容仅保留一条举报，重复提交返回原记录。
 */
@Service
@RequiredArgsConstructor
public class ContentReportService {
    private final ContentReportMapper reports;
    private final ContentTargetService targets;
    private final CurrentActorService actors;

    /**
     * 提交点评或回复举报。
     *
     * <p>身份只来自安全上下文；先检查本人既有举报，保证点击重试不因内容随后被删除而变成
     * 失败。新举报在同一事务内读取目标快照并落库，避免使用加锁前的过期内容。
     */
    @PreAuthorize("isAuthenticated()")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContentReportViews.Submitted submit(
            String targetType, String value, ContentReportRequest request) {
        long targetId = ContentReportPolicy.targetId(value);
        var reason = ContentReportPolicy.reason(request.getReason());
        var detail = ContentReportPolicy.detail(request.getDetail());
        long reporter = actors.requireActor().getUserId();

        var existing = reports.find(reporter, targetType, targetId);
        if (existing != null) {
            return view(existing, true);
        }

        var target = targets.findVisible(targetType, targetId);
        if (target == null) {
            throw new BusinessException(ModerationErrorCode.TARGET_NOT_FOUND);
        }
        if (target.getAuthorUserId() == reporter) {
            throw new BusinessException(ModerationErrorCode.SELF);
        }

        var row = new ContentReportEntity();
        row.setReporterUserId(reporter);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setTargetAuthorUserId(target.getAuthorUserId());
        row.setReviewId(target.getReviewId());
        row.setContentSnapshot(target.getBody());
        row.setReason(reason.name());
        row.setDetail(detail);
        try {
            reports.insert(row);
        } catch (DuplicateKeyException duplicate) {
            // 并发提交由唯一键串行化，返回先落库的举报而不是把冲突暴露给用户。
            existing = reports.find(reporter, targetType, targetId);
            if (existing == null) {
                throw duplicate;
            }
            return view(existing, true);
        }

        var saved = reports.find(reporter, targetType, targetId);
        if (saved == null) {
            throw new IllegalStateException("Missing content report after insert");
        }
        return view(saved, false);
    }

    private ContentReportViews.Submitted view(ContentReportEntity row, boolean duplicate) {
        var result = new ContentReportViews.Submitted();
        result.setReportId(Long.toString(row.getId()));
        result.setTargetType(row.getTargetType());
        result.setTargetId(Long.toString(row.getTargetId()));
        result.setStatus(row.getStatus());
        result.setDuplicate(duplicate);
        result.setCreatedAt(row.getCreatedAt());
        return result;
    }
}
