package com.foodmap.review.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.review.dto.ReviewPublishRequest;
import com.foodmap.review.dto.ReviewSubmissionRequest;
import com.foodmap.review.entity.ReviewSubmissionEntity;
import com.foodmap.review.exception.ReviewErrorCode;
import com.foodmap.review.mapper.ReviewSubmissionMapper;
import com.foodmap.review.policy.ReviewSubmissionPolicy;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 新店审核的点评发布协作入口；不向管理员返回私有内容，只操作review自有表并加入审核事务。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(propagation = Propagation.MANDATORY)
public class ReviewSubmissionPublicationService {
    private final ReviewSubmissionMapper submissions;
    private final ReviewWriteService reviews;

    /** 店铺模块先锁申请，再按申请→点评顺序核对作者、CREATE及最终餐厅类型。 首次通过只接受WAITING；已通过只接受PUBLISHED回执，不为异常的历史通过记录补发点评。 */
    public void validateApproval(
            long requestId,
            long owner,
            String requestType,
            String restaurantType,
            boolean alreadyApproved) {
        var row = submissions.lockByRequest(requestId);
        if (row == null) {
            return;
        }
        if (!Objects.equals(row.getAuthorUserId(), owner)
                || !"CREATE".equals(requestType)
                || !"REGULAR".equals(restaurantType)
                || !(alreadyApproved ? "PUBLISHED" : "WAITING").equals(row.getStatus())) {
            throw new BusinessException(ReviewErrorCode.SUBMISSION_STATE_CONFLICT);
        }
    }

    /**
     * 调用方已持有申请锁并确定新建或已锁定的正式餐厅；正式点评、评分、图片、奖励及清空待发布内容一起提交。
     * 不访问OSS，不重新绑定BOUND文件；任一故障必须抛出给外层回滚，不能捕获后单独结束申请。
     */
    public void publishApproved(long requestId, long restaurantId, long owner) {
        var row = submissions.lockByRequest(requestId);
        if (row == null) {
            return;
        }
        if (!Objects.equals(row.getAuthorUserId(), owner) || !"WAITING".equals(row.getStatus())) {
            throw new BusinessException(ReviewErrorCode.SUBMISSION_STATE_CONFLICT);
        }
        var files = submissions.photoIds(row.getId());
        var request = publicationRequest(row);
        request.setPhotoIds(files.stream().map(String::valueOf).toList());
        // 当前内容摘要独立于原始提交摘要；校验后再转换为包含正式餐厅ID的正式点评摘要。
        var content = new ReviewSubmissionRequest();
        content.setScores(request.getScores());
        content.setRecommendation(request.getRecommendation());
        content.setBody(request.getBody());
        content.setPhotoIds(request.getPhotoIds());
        String expected =
                ReviewSubmissionPolicy.normalize(owner, row.getClientRequestId(), content)
                        .getRequestHash();
        if (!expected.equalsIgnoreCase(row.getContentHash())) {
            throw new BusinessException(ReviewErrorCode.SUBMISSION_STATE_CONFLICT);
        }
        var published = reviews.publishSubmission(restaurantId, owner, request);
        if (submissions.deletePhotos(row.getId()) != files.size()
                || submissions.markPublished(row.getId(), Long.parseLong(published.getReviewId()))
                        != 1) {
            throw new IllegalStateException("Review submission publication receipt inconsistent");
        }
    }

    /** 只在review模块内部转换私有记录；正式发布使用首次预留UUID，保持作者及图片顺序。 */
    private ReviewPublishRequest publicationRequest(ReviewSubmissionEntity row) {
        var request = new ReviewPublishRequest();
        request.setClientRequestId(row.getPublishRequestId());
        var scores = new ReviewPublishRequest.Scores();
        scores.setTaste(row.getTasteScore());
        scores.setValue(row.getValueScore());
        scores.setService(row.getServiceScore());
        scores.setAmbience(row.getAmbienceScore());
        request.setScores(scores);
        request.setRecommendation(row.getRecommendation());
        request.setBody(row.getBody());
        return request;
    }
}
