package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.exception.FileErrorCode;
import com.ginkgo.foodmap.file.service.MediaFileService;
import com.ginkgo.foodmap.review.mapper.ReviewSubmissionMapper;
import com.ginkgo.foodmap.review.vo.ReviewPageVo;
import com.ginkgo.foodmap.review.vo.ReviewSubmissionVo;
import com.ginkgo.foodmap.security.service.CurrentActorService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 附带点评的本人只读入口；管理员身份不豁免作者归属校验，不访问店铺模块数据表。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ReviewSubmissionReadService {
    private final ReviewSubmissionMapper submissions;
    private final CurrentActorService actors;
    private final MediaFileService media;

    /** 加入申请详情的只读快照，保证申请和附带点评来自同一次数据库视图；普通申请返回null。 */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public ReviewSubmissionVo detail(long requestId) {
        long owner = actors.requireActor().getUserId();
        var row = submissions.findOwn(requestId, owner);
        if (row == null) {
            return null;
        }
        var result = new ReviewSubmissionVo();
        result.setId(row.getId().toString());
        result.setStatus(row.getStatus());
        result.setPhotoPositions(List.of());
        if ("PUBLISHED".equals(row.getStatus())) {
            result.setPublishedReviewId(row.getPublishedReviewId().toString());
            return result;
        }
        var scores = new ReviewPageVo.Scores();
        scores.setTaste(row.getTasteScore());
        scores.setValue(row.getValueScore());
        scores.setService(row.getServiceScore());
        scores.setAmbience(row.getAmbienceScore());
        result.setScores(scores);
        result.setRecommendation(row.getRecommendation());
        result.setBody(row.getBody());
        result.setPhotoPositions(submissions.ownPhotoPositions(requestId, owner));
        return result;
    }

    /** 申请服务先验证申请归属和位置；本模块再核对作者、未发布状态及图片关联，OSS读取不占数据库事务。 */
    public byte[] photo(long requestId, int position) {
        long owner = actors.requireActor().getUserId();
        Long fileId = submissions.findOwnPhoto(requestId, owner, position);
        if (fileId == null) {
            throw new BusinessException(FileErrorCode.NOT_FOUND);
        }
        return media.readOwnReviewBound(fileId);
    }
}
