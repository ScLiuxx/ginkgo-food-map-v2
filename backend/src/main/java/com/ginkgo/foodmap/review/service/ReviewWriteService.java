package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.growth.dto.FirstStoreReviewContribution;
import com.ginkgo.foodmap.growth.service.GrowthRewardApplicationService;
import com.ginkgo.foodmap.restaurant.dto.RestaurantReviewDelta;
import com.ginkgo.foodmap.restaurant.service.RestaurantReviewApplicationService;
import com.ginkgo.foodmap.review.dto.ReviewPublishRequest;
import com.ginkgo.foodmap.review.entity.ReviewEntity;
import com.ginkgo.foodmap.review.exception.ReviewErrorCode;
import com.ginkgo.foodmap.review.mapper.ReviewWriteMapper;
import com.ginkgo.foodmap.review.policy.ReviewPublishPolicy;
import com.ginkgo.foodmap.review.vo.ReviewPublishVo;
import com.ginkgo.foodmap.security.service.CurrentActorService;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Objects;

/** 文本发布应用服务；锁父店、写点评和替换代表分数一起提交，重试在完整回滚之后进行。 */
@Service
@RequiredArgsConstructor
public class ReviewWriteService {
    private final ReviewWriteMapper mapper;
    private final com.ginkgo.foodmap.review.mapper.ReviewDeleteMapper deletion;
    private final RestaurantReviewApplicationService restaurants;
    private final CurrentActorService actors;
    private final PlatformTransactionManager transactions;
    private final com.ginkgo.foodmap.file.service.MediaFileService media;
    private final com.ginkgo.foodmap.review.mapper.ReviewPhotoMapper photos;
    private final GrowthRewardApplicationService growth;

    /** 事务最多重试三次，仅处理并发锁/唯一键竞争，不掩盖普通SQL或统计错误。 */
    @PreAuthorize("isAuthenticated()")
    public ReviewPublishVo publish(String restaurantId, ReviewPublishRequest request) {
        var row =
                ReviewPublishPolicy.normalize(
                        restaurantId, actors.requireActor().getUserId(), request);
        var photoIds = ReviewPublishPolicy.photoIds(request);
        for (int attempt = 0; attempt < 3; attempt++) {
            var tx = new TransactionTemplate(transactions);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setTimeout(10);
            try {
                return Objects.requireNonNull(
                        tx.execute(status -> publishTransaction(row, photoIds)));
            } catch (DuplicateKeyException
                    | PessimisticLockingFailureException
                    | QueryTimeoutException
                    | TransactionTimedOutException exception) {
                // 尤其跨店同UUID冲突，必须结束旧事务后再读胜出记录，不能吞异常继续提交。
                if (attempt == 2) {
                    throw new BusinessException(ReviewErrorCode.BUSY);
                }
            }
        }
        throw new IllegalStateException("Unreachable publish state");
    }

    private ReviewPublishVo publishTransaction(ReviewEntity row, java.util.List<Long> photoIds) {
        restaurants.lockVisible(row.getRestaurantId());
        if (deletion.deletedRequest(row.getAuthorUserId(), row.getClientRequestId()) != 0) {
            throw new BusinessException(ReviewErrorCode.REQUEST_DELETED);
        }
        var existing = mapper.request(row.getAuthorUserId(), row.getClientRequestId());
        if (existing != null) {
            if (!sameContent(existing, row) || !photos.ids(existing.getId()).equals(photoIds)) {
                throw new BusinessException(ReviewErrorCode.REQUEST_CONFLICT);
            }
            return result(existing, true);
        }
        var previous = mapper.latest(row.getRestaurantId(), row.getAuthorUserId());
        row.setCreatedAt(previous == null ? null : previous.getCreatedAt());
        row.setId(null);
        mapper.insert(row);
        // 独立回执唯一键使跨店发布/删除竞争仍然安全；重复键由外层完整事务重试。
        deletion.record(row);
        // 必须加入当前点评事务；任一图片失效或归属错误，点评、图片及评分一起回滚。
        media.bind(photoIds, row.getAuthorUserId());
        for (int position = 0; position < photoIds.size(); position++) {
            photos.insert(row.getId(), position, photoIds.get(position));
        }
        restaurants.applyDelta(row.getRestaurantId(), delta(row, previous));
        // 业务资源锁及所有点评写入完成后，加入同一事务计奖；失败一起回滚，幂等回执分支不补发经验。
        var contribution = new FirstStoreReviewContribution();
        contribution.setUserId(row.getAuthorUserId());
        contribution.setRestaurantId(row.getRestaurantId());
        contribution.setReviewId(row.getId());
        growth.grantFirstStoreReview(contribution);
        return result(row, false);
    }

    private boolean sameContent(ReviewEntity a, ReviewEntity b) {
        return a.getRequestHash().equalsIgnoreCase(b.getRequestHash())
                && a.getRestaurantId() == b.getRestaurantId()
                && a.getTasteScore().compareTo(b.getTasteScore()) == 0
                && a.getValueScore().compareTo(b.getValueScore()) == 0
                && a.getServiceScore().compareTo(b.getServiceScore()) == 0
                && a.getAmbienceScore().compareTo(b.getAmbienceScore()) == 0
                && a.isRecommended() == b.isRecommended()
                && a.getBody().equals(b.getBody());
    }

    /** 同人再评只替换旧贡献：总条数由restaurant固定+1，人数仅首次+1。 */
    private RestaurantReviewDelta delta(ReviewEntity row, ReviewEntity old) {
        var d = new RestaurantReviewDelta();
        d.setParticipants(old == null ? 1 : 0);
        d.setRecommendations(
                (row.isRecommended() ? 1 : 0) - (old != null && old.isRecommended() ? 1 : 0));
        d.setTaste(
                row.getTasteScore().subtract(old == null ? BigDecimal.ZERO : old.getTasteScore()));
        d.setValue(
                row.getValueScore().subtract(old == null ? BigDecimal.ZERO : old.getValueScore()));
        d.setService(
                row.getServiceScore()
                        .subtract(old == null ? BigDecimal.ZERO : old.getServiceScore()));
        d.setAmbience(
                row.getAmbienceScore()
                        .subtract(old == null ? BigDecimal.ZERO : old.getAmbienceScore()));
        d.setOverall(
                row.getOverallScore()
                        .subtract(old == null ? BigDecimal.ZERO : old.getOverallScore()));
        return d;
    }

    private ReviewPublishVo result(ReviewEntity row, boolean replayed) {
        var vo = new ReviewPublishVo();
        vo.setReviewId(Long.toString(row.getId()));
        vo.setRestaurantId(Long.toString(row.getRestaurantId()));
        vo.setReplayed(replayed);
        return vo;
    }
}
