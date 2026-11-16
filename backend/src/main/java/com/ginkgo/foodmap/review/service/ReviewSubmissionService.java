package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.service.MediaFileService;
import com.ginkgo.foodmap.review.dto.ReviewSubmissionRequest;
import com.ginkgo.foodmap.review.entity.ReviewSubmissionEntity;
import com.ginkgo.foodmap.review.exception.ReviewErrorCode;
import com.ginkgo.foodmap.review.mapper.ReviewSubmissionMapper;
import com.ginkgo.foodmap.review.policy.ReviewSubmissionPolicy;
import com.ginkgo.foodmap.review.vo.ReviewSubmissionReceipt;
import com.ginkgo.foodmap.security.service.CurrentActorService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** review模块的联合提交入口；申请归属、CREATE类型和申请锁由restaurant服务先验证。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ReviewSubmissionService {
    private final ReviewSubmissionMapper submissions;
    private final MediaFileService media;
    private final CurrentActorService actors;

    /** 只校验及计算点评摘要，供外层联合摘要使用；不提前绑定文件，不写入任何数据。 */
    public String fingerprint(String uuid, ReviewSubmissionRequest input) {
        return ReviewSubmissionPolicy.normalize(actors.requireActor().getUserId(), uuid, input)
                .getRequestHash();
    }

    /** 必须加入持有申请行锁的外层事务；任意文件或关联失败会回滚店铺申请和两类图片绑定。 重放先核对最初请求摘要，再返回原回执；不能再次绑定已BOUND的文件或更换预留发布UUID。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReviewSubmissionReceipt saveWaiting(
            long requestId, String uuid, ReviewSubmissionRequest input) {
        long owner = actors.requireActor().getUserId();
        var normalized = ReviewSubmissionPolicy.normalize(owner, uuid, input);
        var saved = submissions.lockByRequest(requestId);
        if (saved == null) {
            // 驳回重提迁移后，原投稿重试仍确认同一条回执，不能在旧申请下重新绑定图片或插入点评。
            saved = submissions.lockByClient(owner, uuid);
        }
        if (saved != null) {
            if (!Objects.equals(saved.getAuthorUserId(), owner)
                    || !Objects.equals(saved.getOriginRequestId(), requestId)
                    || !saved.getClientRequestId().equalsIgnoreCase(uuid)
                    || !saved.getRequestHash().equalsIgnoreCase(normalized.getRequestHash())) {
                throw new BusinessException(ReviewErrorCode.REQUEST_CONFLICT);
            }
            return receipt(saved);
        }
        var row = new ReviewSubmissionEntity();
        row.setRestaurantRequestId(requestId);
        row.setOriginRequestId(requestId);
        row.setAuthorUserId(owner);
        row.setTasteScore(normalized.getTasteScore());
        row.setValueScore(normalized.getValueScore());
        row.setServiceScore(normalized.getServiceScore());
        row.setAmbienceScore(normalized.getAmbienceScore());
        row.setRecommendation(normalized.getRecommendation());
        row.setBody(normalized.getBody());
        row.setClientRequestId(normalized.getClientRequestId());
        row.setRequestHash(normalized.getRequestHash());
        row.setContentHash(normalized.getRequestHash());
        row.setPublishRequestId(UUID.randomUUID().toString());
        row.setStatus("WAITING");
        if (submissions.insert(row) != 1) {
            throw new IllegalStateException("Missing review submission insert");
        }
        var photos = ReviewSubmissionPolicy.photoIds(input);
        media.bind(photos, owner);
        for (int position = 0; position < photos.size(); position++) {
            if (submissions.insertPhoto(row.getId(), position, photos.get(position)) != 1) {
                throw new IllegalStateException("Missing review submission photo insert");
            }
        }
        return receipt(row);
    }

    private ReviewSubmissionReceipt receipt(ReviewSubmissionEntity row) {
        var result = new ReviewSubmissionReceipt();
        result.setId(row.getId().toString());
        result.setStatus(row.getStatus());
        return result;
    }
}
