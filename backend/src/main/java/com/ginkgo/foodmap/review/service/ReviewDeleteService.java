package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.service.MediaFileService;
import com.ginkgo.foodmap.restaurant.dto.RestaurantReviewDelta;
import com.ginkgo.foodmap.restaurant.service.RestaurantReviewApplicationService;
import com.ginkgo.foodmap.review.entity.ReviewEntity;
import com.ginkgo.foodmap.review.exception.ReviewErrorCode;
import com.ginkgo.foodmap.review.mapper.*;
import com.ginkgo.foodmap.review.policy.ReviewReadPolicy;
import com.ginkgo.foodmap.security.service.CurrentActorService;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

/** 作者硬删除应用服务；父锁、回执、子资源与评分同事务提交，OSS清理由后台重试。 */
@Service
@RequiredArgsConstructor
public class ReviewDeleteService {
    private final ReviewDeleteMapper deletion;
    private final ReviewWriteMapper reviews;
    private final ReviewPhotoMapper photos;
    private final ReplyDeleteMapper replyReceipts;
    private final MediaFileService media;
    private final RestaurantReviewApplicationService restaurants;
    private final CurrentActorService actors;
    private final PlatformTransactionManager transactions;

    /** 身份仅来自安全上下文；与发布相同锁顺序和有界事务重试，不开放管理员代删。 */
    @PreAuthorize("isAuthenticated()")
    public void remove(String value) {
        long id = ReviewReadPolicy.restaurantId(value);
        long owner = actors.requireActor().getUserId();
        for (int attempt = 0; attempt < 3; attempt++) {
            var tx = new TransactionTemplate(transactions);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setTimeout(10);
            try {
                tx.executeWithoutResult(status -> removeTransaction(id, owner));
                return;
            } catch (PessimisticLockingFailureException
                    | QueryTimeoutException
                    | TransactionTimedOutException e) {
                if (attempt == 2) {
                    throw new BusinessException(ReviewErrorCode.BUSY);
                }
            }
        }
    }

    private void removeTransaction(long id, long owner) {
        var row = deletion.find(id);
        if (row == null) {
            requireDeletedOwner(id, owner);
            return;
        }
        // 父店锁先于子资源锁，和发评、回复、删店统一顺序。
        restaurants.lockVisible(row.getRestaurantId());
        row = deletion.find(id);
        if (row == null) {
            requireDeletedOwner(id, owner);
            return;
        }
        if (row.getAuthorUserId() != owner) {
            throw new BusinessException(ReviewErrorCode.FORBIDDEN);
        }
        var previous = reviews.latest(row.getRestaurantId(), owner);
        var fileIds = photos.ids(id);
        if (deletion.tombstone(id) != 1) {
            throw new IllegalStateException("Missing review request receipt");
        }
        media.releaseBound(fileIds, owner);
        replyReceipts.tombstoneReview(id);
        deletion.replies(id);
        deletion.photos(id);
        if (deletion.review(id) != 1) {
            throw new IllegalStateException("Missing review during delete");
        }
        var next = reviews.latest(row.getRestaurantId(), owner);
        restaurants.applyDelta(row.getRestaurantId(), delta(previous, next));
    }

    /** 原作者重试已完成的删除得到204；其他账号仍403，未知ID为404。 */
    private void requireDeletedOwner(long id, long owner) {
        Long deletedOwner = deletion.deletedOwner(id);
        if (deletedOwner == null) {
            throw new BusinessException(ReviewErrorCode.NOT_FOUND);
        }
        if (deletedOwner != owner) {
            throw new BusinessException(ReviewErrorCode.FORBIDDEN);
        }
    }

    /** 历史删除时新旧代表相同、分数差为零；最后一条删除时人数及贡献全部扣除。 */
    private RestaurantReviewDelta delta(ReviewEntity old, ReviewEntity next) {
        var d = new RestaurantReviewDelta();
        d.setReviews(-1);
        d.setParticipants(next == null ? -1 : 0);
        d.setRecommendations(
                (next != null && next.isRecommended() ? 1 : 0) - (old.isRecommended() ? 1 : 0));
        d.setTaste(
                (next == null ? BigDecimal.ZERO : next.getTasteScore())
                        .subtract(old.getTasteScore()));
        d.setValue(
                (next == null ? BigDecimal.ZERO : next.getValueScore())
                        .subtract(old.getValueScore()));
        d.setService(
                (next == null ? BigDecimal.ZERO : next.getServiceScore())
                        .subtract(old.getServiceScore()));
        d.setAmbience(
                (next == null ? BigDecimal.ZERO : next.getAmbienceScore())
                        .subtract(old.getAmbienceScore()));
        d.setOverall(
                (next == null ? BigDecimal.ZERO : next.getOverallScore())
                        .subtract(old.getOverallScore()));
        return d;
    }
}
