package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.service.MediaFileService;
import com.ginkgo.foodmap.restaurant.dto.RestaurantReviewDelta;
import com.ginkgo.foodmap.restaurant.service.CanteenWindowService;
import com.ginkgo.foodmap.restaurant.service.RestaurantReviewApplicationService;
import com.ginkgo.foodmap.review.entity.ReviewEntity;
import com.ginkgo.foodmap.review.exception.ReviewErrorCode;
import com.ginkgo.foodmap.review.mapper.ReplyDeleteMapper;
import com.ginkgo.foodmap.review.mapper.ReviewDeleteMapper;
import com.ginkgo.foodmap.review.mapper.ReviewPhotoMapper;
import com.ginkgo.foodmap.review.mapper.ReviewWriteMapper;
import com.ginkgo.foodmap.review.policy.ReviewReadPolicy;
import com.ginkgo.foodmap.security.service.CurrentActorService;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

/** 作者硬删除应用服务；父锁、回执、子资源与评分同事务提交，OSS清理由后台重试。 */
@Service
@RequiredArgsConstructor
public class ReviewDeleteService {
    private final ReviewDeleteMapper deletion;
    private final CanteenWindowService windows;
    private final ReviewWriteMapper reviews;
    private final ReviewPhotoMapper photos;
    private final ReplyDeleteMapper replyReceipts;
    private final MediaFileService media;
    private final RestaurantReviewApplicationService restaurants;
    private final CurrentActorService actors;
    private final PlatformTransactionManager transactions;

    /** 身份仅来自安全上下文；与发布相同锁顺序和有界事务重试。 */
    @PreAuthorize("isAuthenticated()")
    public void remove(String value) {
        long id = ReviewReadPolicy.restaurantId(value);
        delete(id, actors.requireActor().getUserId());
    }

    /**
     * 管理员处置违规点评：跳过作者归属校验，但沿用同一父锁、回执、子资源和评分重算。
     *
     * <p>返回true表示本次删除，false表示此前已删除；删除回执继续阻止原发布请求重放，不能借此
     * 恢复内容。权限由管理安全链和方法注解双重校验。
     */
    @PreAuthorize("hasRole('ADMIN')")
    public boolean removeForModeration(long id) {
        return delete(id, null);
    }

    /** owner为null表示管理员处置；重试、超时和锁顺序与作者删除完全一致。 */
    private boolean delete(long id, Long owner) {
        for (int attempt = 0; ; attempt++) {
            var tx = new TransactionTemplate(transactions);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setTimeout(10);
            try {
                return Boolean.TRUE.equals(tx.execute(status -> removeTransaction(id, owner)));
            } catch (PessimisticLockingFailureException
                    | QueryTimeoutException
                    | TransactionTimedOutException e) {
                if (attempt == 2) {
                    throw new BusinessException(ReviewErrorCode.BUSY);
                }
            }
        }
    }

    private boolean removeTransaction(long id, Long owner) {
        var row = deletion.find(id);
        if (row == null) {
            requireDeletedOwner(id, owner);
            return false;
        }
        // 父店锁先于子资源锁，和发评、回复、删店统一顺序。
        if (row.getWindowId() == null) {
            restaurants.lockVisible(row.getRestaurantId());
        } else {
            windows.lockVisible(row.getWindowId(), false);
        }
        row = deletion.find(id);
        if (row == null) {
            requireDeletedOwner(id, owner);
            return false;
        }
        // 代表点评、图片归属和统计重算都取内容作者；管理员身份不能替换作者。
        long author = row.getAuthorUserId();
        if (owner != null && author != owner.longValue()) {
            throw new BusinessException(ReviewErrorCode.FORBIDDEN);
        }
        var previous =
                (row.getWindowId() == null
                        ? reviews.latest(row.getRestaurantId(), author)
                        : reviews.latestWindow(row.getWindowId(), author));
        var fileIds = photos.ids(id);
        if (deletion.tombstone(id) != 1) {
            throw new IllegalStateException("Missing review request receipt");
        }
        // 图片登记始终属于内容作者，管理员处置也不能把作者的上传记录改成处理人。
        media.releaseBound(fileIds, author);
        replyReceipts.tombstoneReview(id);
        deletion.replies(id);
        deletion.photos(id);
        if (deletion.review(id) != 1) {
            throw new IllegalStateException("Missing review during delete");
        }
        var next =
                (row.getWindowId() == null
                        ? reviews.latest(row.getRestaurantId(), author)
                        : reviews.latestWindow(row.getWindowId(), author));
        if (row.getWindowId() == null) {
            restaurants.applyDelta(row.getRestaurantId(), delta(previous, next));
        } else {
            windows.applyDelta(row.getWindowId(), delta(previous, next));
        }
        return true;
    }

    /** 原作者重试已完成的删除得到204；其他账号仍403，未知ID为404；管理员跳过归属校验。 */
    private void requireDeletedOwner(long id, Long owner) {
        Long deletedOwner = deletion.deletedOwner(id);
        if (deletedOwner == null) {
            throw new BusinessException(ReviewErrorCode.NOT_FOUND);
        }
        if (owner != null && deletedOwner.longValue() != owner) {
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
