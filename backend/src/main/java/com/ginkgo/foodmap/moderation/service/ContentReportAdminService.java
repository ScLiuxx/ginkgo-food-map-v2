package com.ginkgo.foodmap.moderation.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.moderation.dto.ContentReportDismissRequest;
import com.ginkgo.foodmap.moderation.dto.ContentReportQuery;
import com.ginkgo.foodmap.moderation.dto.ContentReportRemoveRequest;
import com.ginkgo.foodmap.moderation.entity.ContentReportEntity;
import com.ginkgo.foodmap.moderation.exception.ModerationErrorCode;
import com.ginkgo.foodmap.moderation.mapper.ContentReportMapper;
import com.ginkgo.foodmap.moderation.policy.ContentReportPolicy;
import com.ginkgo.foodmap.moderation.vo.ContentReportViews;
import com.ginkgo.foodmap.review.service.ReviewModerationService;
import com.ginkgo.foodmap.security.service.CurrentActorService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 管理员举报查询与驳回应用服务。
 *
 * <p>本步骤只读取举报和自己的正文快照，不读取也不修改点评、回复、评分或成长数据。同一内容的
 * 多份待处理举报按RISK-026一并驳回；已经删除内容的举报不能被驳回。
 */
@Service
@RequiredArgsConstructor
public class ContentReportAdminService {
    private final ContentReportMapper reports;
    private final ReviewModerationService reviewModeration;
    private final CurrentActorService actors;

    /** 状态筛选和分页均有界；count与records在同一只读事务内取得。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ContentReportViews.AdminPage page(ContentReportQuery query) {
        long total = reports.countByStatus(query.getStatus());
        long offset = (long) (query.getPage() - 1) * query.getPageSize();
        List<ContentReportViews.AdminItem> records =
                reports.pageByStatus(query.getStatus(), offset, query.getPageSize()).stream()
                        .map(this::view)
                        .toList();

        var result = new ContentReportViews.AdminPage();
        result.setRecords(records);
        result.setTotal(total);
        result.setTotalPages((total + query.getPageSize() - 1) / query.getPageSize());
        result.setPage(query.getPage());
        result.setPageSize(query.getPageSize());
        return result;
    }

    /** 举报编号不存在返回404，不泄露其他状态或目标信息。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ContentReportViews.AdminItem detail(String value) {
        long id = ContentReportPolicy.reportId(value);
        var row = reports.findById(id);
        if (row == null) {
            throw new BusinessException(ModerationErrorCode.NOT_FOUND);
        }
        return view(row);
    }

    /**
     * 驳回举报并记录处理人、时间和可选备注。
     *
     * <p>同一内容下所有PENDING举报一并驳回，避免管理员逐条重复处理。重复驳回返回当前状态且
     * affectedReports为0；已删除内容的RESOLVED举报返回409，不能反向改写处置结果。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContentReportViews.Dismissed dismiss(String value, ContentReportDismissRequest request) {
        long id = ContentReportPolicy.reportId(value);
        String note =
                ContentReportPolicy.decisionNote(request == null ? null : request.getNote());
        long handler = actors.requireActor().getUserId();

        var row = reports.findById(id);
        if (row == null) {
            throw new BusinessException(ModerationErrorCode.NOT_FOUND);
        }
        if ("RESOLVED".equals(row.getStatus())) {
            throw new BusinessException(ModerationErrorCode.STATE_CONFLICT);
        }
        if ("DISMISSED".equals(row.getStatus())) {
            return receipt(row, 0);
        }

        Instant handledAt = Instant.now();
        int affected =
                reports.dismissPendingTarget(
                        row.getTargetType(), row.getTargetId(), handler, handledAt, note);
        var saved = reports.findById(id);
        if (saved == null) {
            throw new IllegalStateException("Missing content report after dismiss");
        }
        if ("RESOLVED".equals(saved.getStatus())) {
            throw new BusinessException(ModerationErrorCode.STATE_CONFLICT);
        }
        return receipt(saved, affected);
    }

    /**
     * 确认违规并永久删除点评或回复，再把同一内容下所有待处理举报结案。
     *
     * <p>内容删除在review模块自己的有界事务中提交，举报结案随后独立提交，不引入跨模块分布式事务。
     * 两步之间失败时内容已删除但举报仍为PENDING；管理员重试会得到contentDeleted=false并完成结案，
     * 因此该顺序是可补偿且幂等的。
     */
    @PreAuthorize("hasRole('ADMIN')")
    public ContentReportViews.Removed remove(
            String value, ContentReportRemoveRequest request) {
        long id = ContentReportPolicy.reportId(value);
        String note =
                ContentReportPolicy.decisionNote(request == null ? null : request.getNote());
        long handler = actors.requireActor().getUserId();

        var row = reports.findById(id);
        if (row == null) {
            throw new BusinessException(ModerationErrorCode.NOT_FOUND);
        }
        if ("DISMISSED".equals(row.getStatus())) {
            throw new BusinessException(ModerationErrorCode.STATE_CONFLICT);
        }
        if ("RESOLVED".equals(row.getStatus())) {
            return removalReceipt(row, 0, false);
        }

        // 永久删除不可恢复；作者已先删除时返回false，不影响后续举报结案。
        boolean deleted = reviewModeration.remove(row.getTargetType(), row.getTargetId());
        Instant handledAt = Instant.now();
        int affected =
                reports.resolvePendingTarget(
                        row.getTargetType(), row.getTargetId(), handler, handledAt, note);
        var saved = reports.findById(id);
        if (saved == null) {
            throw new IllegalStateException("Missing content report after removal");
        }
        return removalReceipt(saved, affected, deleted);
    }

    private ContentReportViews.Removed removalReceipt(
            ContentReportEntity row, int affected, boolean deleted) {
        var result = new ContentReportViews.Removed();
        result.setReportId(Long.toString(row.getId()));
        result.setTargetType(row.getTargetType());
        result.setTargetId(Long.toString(row.getTargetId()));
        result.setStatus(row.getStatus());
        result.setAffectedReports(affected);
        result.setContentDeleted(deleted);
        result.setHandledAt(row.getHandledAt());
        return result;
    }

    private ContentReportViews.Dismissed receipt(ContentReportEntity row, int affected) {
        var result = new ContentReportViews.Dismissed();
        result.setReportId(Long.toString(row.getId()));
        result.setStatus(row.getStatus());
        result.setAffectedReports(affected);
        result.setHandledAt(row.getHandledAt());
        return result;
    }

    private ContentReportViews.AdminItem view(ContentReportEntity row) {
        var item = new ContentReportViews.AdminItem();
        item.setId(Long.toString(row.getId()));
        item.setTargetType(row.getTargetType());
        item.setTargetId(Long.toString(row.getTargetId()));
        item.setReviewId(Long.toString(row.getReviewId()));
        item.setReporterUserId(Long.toString(row.getReporterUserId()));
        item.setTargetAuthorUserId(Long.toString(row.getTargetAuthorUserId()));
        item.setContentSnapshot(row.getContentSnapshot());
        item.setReason(row.getReason());
        item.setDetail(row.getDetail());
        item.setStatus(row.getStatus());
        item.setHandledBy(row.getHandledBy() == null ? null : Long.toString(row.getHandledBy()));
        item.setHandledAt(row.getHandledAt());
        item.setDecisionNote(row.getDecisionNote());
        item.setCreatedAt(row.getCreatedAt());
        return item;
    }
}
