package com.foodmap.review.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.file.service.MediaFileService;
import com.foodmap.review.dto.ReviewSubmissionRequest;
import com.foodmap.review.entity.ReviewSubmissionEntity;
import com.foodmap.review.exception.ReviewErrorCode;
import com.foodmap.review.mapper.ReviewSubmissionMapper;
import com.foodmap.review.policy.ReviewSubmissionPolicy;
import com.foodmap.security.service.CurrentActorService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 店铺申请生命周期的点评协作入口；父申请归属与状态由店铺服务锁定验证，点评及图片始终同事务处理。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Transactional(propagation = Propagation.MANDATORY)
public class ReviewSubmissionLifecycleService {
    private final ReviewSubmissionMapper submissions;
    private final ReviewSubmissionService creation;
    private final MediaFileService media;
    private final CurrentActorService actors;

    /** 管理员只验证原作者和待发布状态，不读取HTTP点评内容；驳回保留WAITING，等待本人重提。 */
    @PreAuthorize("hasRole('ADMIN')")
    public void validateRejection(long requestId, long owner, String requestType) {
        var row = submissions.lockByRequest(requestId);
        if (row != null) {
            requireWaiting(row, owner);
            requireTarget(requestType, "REGULAR");
        }
    }

    /** 本人待审修改可沿用、整体更新或首次附带点评；返回当前摘要供店铺联合摘要使用，不暴露内部实体。 */
    public String edit(
            long requestId,
            String uuid,
            String requestType,
            String restaurantType,
            ReviewSubmissionRequest input) {
        long owner = actors.requireActor().getUserId();
        var row = submissions.lockByRequest(requestId);
        if (row == null && input == null) {
            return null;
        }
        requireTarget(requestType, restaurantType);
        if (row == null) {
            creation.saveWaiting(requestId, uuid, input);
            return ReviewSubmissionPolicy.normalize(owner, uuid, input).getRequestHash();
        }
        requireWaiting(row, owner);
        if (input != null) {
            replaceContent(row, input);
        }
        return row.getContentHash();
    }

    /** 本人撤回和申请撤回一起提交；保留内容与BOUND图片供本人查看，终态不能重新发布。 */
    public void withdraw(long requestId) {
        long owner = actors.requireActor().getUserId();
        var row = submissions.lockByRequest(requestId);
        if (row == null) {
            return;
        }
        requireWaiting(row, owner);
        if (submissions.withdraw(row.getId(), owner) != 1) {
            throw new IllegalStateException("Missing review submission withdrawal");
        }
    }

    /**
     * 原申请和新申请已按顺序锁定；仅首次重提执行迁移，重放由店铺服务提前返回，不能覆盖后来的编辑。 缺省沿用，完整对象更新；移动同一记录保持首次来源、摘要和发布UUID，避免重复待发布内容。
     */
    public void resubmit(
            long previous,
            long next,
            String uuid,
            String requestType,
            String restaurantType,
            ReviewSubmissionRequest input) {
        long owner = actors.requireActor().getUserId();
        var row = submissions.lockByRequest(previous);
        if (row == null && input == null) {
            return;
        }
        requireTarget(requestType, restaurantType);
        if (row == null) {
            creation.saveWaiting(next, uuid, input);
            return;
        }
        requireWaiting(row, owner);
        if (input != null) {
            replaceContent(row, input);
        }
        if (submissions.move(row.getId(), previous, next, owner) != 1) {
            throw new IllegalStateException("Missing review submission transfer");
        }
    }

    /** 当前内容、图片释放/绑定及关联替换共用父事务；首次请求摘要保持不变，失败不留下半次编辑。 */
    private void replaceContent(ReviewSubmissionEntity row, ReviewSubmissionRequest input) {
        var normalized =
                ReviewSubmissionPolicy.normalize(
                        row.getAuthorUserId(), row.getClientRequestId(), input);
        var previous = submissions.photoIds(row.getId());
        var replacement = ReviewSubmissionPolicy.photoIds(input);
        media.replaceOwnReviewPhotos(previous, replacement);
        if (!previous.equals(replacement)) {
            if (submissions.deletePhotos(row.getId()) != previous.size()) {
                throw new IllegalStateException("Missing old review submission photos");
            }
            for (int position = 0; position < replacement.size(); position++) {
                if (submissions.insertPhoto(row.getId(), position, replacement.get(position))
                        != 1) {
                    throw new IllegalStateException("Missing replacement review submission photo");
                }
            }
        }
        if (normalized.getRequestHash().equalsIgnoreCase(row.getContentHash())) {
            return;
        }
        row.setTasteScore(normalized.getTasteScore());
        row.setValueScore(normalized.getValueScore());
        row.setServiceScore(normalized.getServiceScore());
        row.setAmbienceScore(normalized.getAmbienceScore());
        row.setRecommendation(normalized.getRecommendation());
        row.setBody(normalized.getBody());
        row.setContentHash(normalized.getRequestHash());
        if (submissions.updateContent(row) != 1) {
            throw new IllegalStateException("Missing review submission content update");
        }
    }

    /** 校验当前作者及WAITING，不允许跨账号操作或借修改、重提恢复已发布/撤回内容。 */
    private void requireWaiting(ReviewSubmissionEntity row, long owner) {
        if (!Objects.equals(row.getAuthorUserId(), owner) || !"WAITING".equals(row.getStatus())) {
            throw new BusinessException(ReviewErrorCode.SUBMISSION_STATE_CONFLICT);
        }
    }

    /** 附带点评只服务于普通餐厅新建；纠错或食堂整体不得因新增修改入口绕过目标限制。 */
    private void requireTarget(String requestType, String restaurantType) {
        if (!"CREATE".equals(requestType) || !"REGULAR".equals(restaurantType)) {
            throw new BusinessException(ReviewErrorCode.SUBMISSION_STATE_CONFLICT);
        }
    }
}
