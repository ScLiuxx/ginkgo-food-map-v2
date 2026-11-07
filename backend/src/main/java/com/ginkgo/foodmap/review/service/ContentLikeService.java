package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.growth.service.GrowthRewardApplicationService;
import com.ginkgo.foodmap.notification.dto.ContentLikeNotification;
import com.ginkgo.foodmap.notification.service.NotificationPublicationService;
import com.ginkgo.foodmap.restaurant.service.RestaurantReviewApplicationService;
import com.ginkgo.foodmap.restaurant.service.RestaurantReviewTargetService;
import com.ginkgo.foodmap.review.entity.ContentLikeEntity;
import com.ginkgo.foodmap.review.entity.LikeTargetEntity;
import com.ginkgo.foodmap.review.exception.ContentLikeErrorCode;
import com.ginkgo.foodmap.review.mapper.ContentLikeMapper;
import com.ginkgo.foodmap.review.mapper.ReplyMapper;
import com.ginkgo.foodmap.review.vo.ContentLikeViews;
import com.ginkgo.foodmap.security.service.CurrentActorService;
import com.ginkgo.foodmap.user.service.PublicAuthorService;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 关系由review拥有；跨模块仅调用公开服务。写入锁顺序始终为父店、点评、关系、成长账户、通知。 */
@Service
@RequiredArgsConstructor
public class ContentLikeService {
    private final ContentLikeMapper likeMapper;
    private final ReplyMapper replyMapper;
    private final RestaurantReviewApplicationService restaurantReviews;
    private final RestaurantReviewTargetService restaurantTargets;
    private final CurrentActorService currentActors;
    private final NotificationPublicationService notifications;
    private final GrowthRewardApplicationService growth;
    private final PublicAuthorService publicAuthors;
    private final PlatformTransactionManager transactionManager;

    /** PUT/DELETE表示目标状态，重复调用不反转；瞬态错误必须退出整笔事务后再重试。 */
    @PreAuthorize("isAuthenticated()")
    public ContentLikeViews.State set(String type, String value, boolean active) {
        validateTargetType(type);
        long id = parseId(value);
        long user = currentActors.requireActor().getUserId();
        Instant occurred = Instant.now();
        for (int attempt = 0; attempt < 3; attempt++) {
            var tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setTimeout(10);
            try {
                return Objects.requireNonNull(
                        tx.execute(status -> write(type, id, user, active, occurred)));
            } catch (DuplicateKeyException
                    | PessimisticLockingFailureException
                    | QueryTimeoutException
                    | TransactionTimedOutException failure) {
                if (attempt == 2) {
                    throw new BusinessException(ContentLikeErrorCode.BUSY);
                }
            }
        }
        throw new IllegalStateException("Unreachable like state");
    }

    private ContentLikeViews.State write(
            String type, long id, long user, boolean active, Instant occurred) {
        var source = requireSource(type, id);
        if (source.getRestaurantId() == null) {
            throw missing();
        }
        restaurantReviews.lockVisibleParent(source.getRestaurantId());
        if (!source.getRestaurantId().equals(replyMapper.lockReview(source.getReviewId()))) {
            throw missing();
        }
        // 父店/点评锁阻止源删除；重新读取回复作者，禁止使用加锁前失效的目标事实。
        source = requireSource(type, id);
        if (source.getAuthorUserId() == user) {
            throw new BusinessException(ContentLikeErrorCode.SELF);
        }
        var row = likeMapper.lock(type, id, user);
        if (row == null && active) {
            createLikeAndNotify(type, id, user, occurred, source);
        } else if (row != null) {
            likeMapper.setActive(row.getId(), active);
        }
        return readState(type, id, user, true);
    }

    /** 沿用外层点赞事务：先保存关系，再计奖和发通知，任一步失败均整体回滚。 */
    private void createLikeAndNotify(
            String type, long id, long user, Instant occurred, LikeTargetEntity source) {
        var row = new ContentLikeEntity();
        row.setTargetType(type);
        row.setTargetId(id);
        row.setUserId(user);
        likeMapper.insert(row);
        if ("REVIEW".equals(type)) {
            growth.grantReviewLike(source.getAuthorUserId(), user, id, occurred);
        }
        var notification = new ContentLikeNotification();
        notification.setRecipientUserId(source.getAuthorUserId());
        notification.setActorUserId(user);
        notification.setLikeId(row.getId());
        notification.setReviewId(source.getReviewId());
        notification.setTargetType(type);
        notifications.contentLike(notification);
    }

    /** 一批最多50条，在同一快照批量核验源、父店和计数；游客仅有公开计数，没有身份状态。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContentLikeViews.Batch batch(String type, String values) {
        validateTargetType(type);
        if (values == null || values.length() > 1000) {
            throw new BusinessException(ContentLikeErrorCode.INVALID);
        }
        var pieces = values.split(",", -1);
        if (pieces.length < 1 || pieces.length > 50) {
            throw new BusinessException(ContentLikeErrorCode.INVALID);
        }
        var ids = Arrays.stream(pieces).map(ContentLikeService::parseId).distinct().toList();
        Long user = viewer();
        var rows = likeMapper.targets(type, ids);
        var visible =
                restaurantTargets.visible(
                        rows.stream()
                                .map(LikeTargetEntity::getRestaurantId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList());
        var valid =
                rows.stream()
                        .filter(r -> visible.containsKey(r.getRestaurantId()))
                        .map(LikeTargetEntity::getId)
                        .collect(Collectors.toSet());
        var counts =
                likeMapper.counts(type, ids, user).stream()
                        .collect(Collectors.toMap(ContentLikeViews.State::getTargetId, r -> r));
        var result = new ContentLikeViews.Batch();
        result.setType(type);
        result.setUserId(user == null ? null : user.toString());
        result.setRecords(
                ids.stream().map(targetId -> toBatchState(targetId, counts, valid)).toList());
        return result;
    }

    private ContentLikeViews.State toBatchState(
            long targetId, Map<String, ContentLikeViews.State> counts, Set<Long> visibleTargetIds) {
        String key = Long.toString(targetId);
        ContentLikeViews.State result = counts.getOrDefault(key, new ContentLikeViews.State());
        result.setTargetId(key);
        result.setAvailable(visibleTargetIds.contains(targetId));
        if (!result.isAvailable()) {
            result.setCount(0);
            result.setLiked(false);
        }
        return result;
    }

    /** 内部主页读取当前有效内容获赞；调用方先核验主体及访问权限，不新增HTTP入口。 */
    @Transactional(readOnly = true)
    public long receivedByAuthor(long user) {
        if (user <= 0) {
            throw new IllegalArgumentException("Invalid author");
        }
        return likeMapper.received(user);
    }

    /** 个人获赞仅统计仍存在的点评与回复，内容删除自然移出合计；不计历史点赞次数。 */
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContentLikeViews.Total received() {
        long user = currentActors.requireActor().getUserId();
        var result = new ContentLikeViews.Total();
        result.setUserId(Long.toString(user));
        result.setCount(likeMapper.received(user));
        return result;
    }

    /** 收件人是被赞内容作者；取消点赞仍能打开原内容，越权或源失效统一404。 */
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContentLikeViews.Target target(String value) {
        long user = currentActors.requireActor().getUserId();
        var row = likeMapper.find(parseId(value));
        if (row == null) {
            throw missing();
        }
        var source = requireSource(row.getTargetType(), row.getTargetId());
        if (source.getAuthorUserId() != user || source.getRestaurantId() == null) {
            throw missing();
        }
        if (restaurantTargets.visible(List.of(source.getRestaurantId())).isEmpty()) {
            throw missing();
        }
        var name = publicAuthors.find(List.of(user)).get(Long.toString(user));
        if (name == null) {
            throw missing();
        }
        var result = new ContentLikeViews.Target();
        result.setUserId(Long.toString(user));
        result.setLikeId(value);
        result.setTargetId(Long.toString(row.getTargetId()));
        result.setTargetType(row.getTargetType());
        result.setReviewId(Long.toString(source.getReviewId()));
        result.setRestaurantId(source.getRestaurantId().toString());
        result.setAuthor(name);
        result.setBody(source.getBody());
        result.setCreatedAt(source.getCreatedAt());
        return result;
    }

    private LikeTargetEntity requireSource(String type, long id) {
        var rows = likeMapper.targets(type, List.of(id));
        if (rows.isEmpty()) {
            throw missing();
        }
        return rows.getFirst();
    }

    private ContentLikeViews.State readState(String type, long id, Long user, boolean available) {
        var rows = likeMapper.counts(type, List.of(id), user);
        var result = rows.isEmpty() ? new ContentLikeViews.State() : rows.getFirst();
        result.setTargetId(Long.toString(id));
        result.setAvailable(available);
        return result;
    }

    private Long viewer() {
        return SecurityContextHolder.getContext().getAuthentication()
                        instanceof JwtAuthenticationToken
                ? currentActors.requireActor().getUserId()
                : null;
    }

    private static BusinessException missing() {
        return new BusinessException(ContentLikeErrorCode.MISSING);
    }

    private static void validateTargetType(String type) {
        if (!"REVIEW".equals(type) && !"REPLY".equals(type)) {
            throw new BusinessException(ContentLikeErrorCode.INVALID);
        }
    }

    private static long parseId(String value) {
        if (value == null
                || !value.matches("[1-9][0-9]{0,18}")
                || new BigInteger(value).bitLength() > 63) {
            throw new BusinessException(ContentLikeErrorCode.INVALID);
        }
        return Long.parseLong(value);
    }
}
