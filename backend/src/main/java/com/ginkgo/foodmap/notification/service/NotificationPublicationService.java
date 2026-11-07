package com.ginkgo.foodmap.notification.service;

import com.ginkgo.foodmap.notification.dto.RestaurantDecisionNotification;
import com.ginkgo.foodmap.notification.dto.ReviewReplyNotification;
import com.ginkgo.foodmap.notification.entity.NotificationEntity;
import com.ginkgo.foodmap.notification.mapper.NotificationMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.util.Objects;

/** 只供已验证业务事实的模块协作调用；通知失败必须让审核和经验一并回滚，不使用提交后监听。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class NotificationPublicationService {
    private final NotificationMapper messages;

    /** 业务锁先于成长锁和通知唯一键；幂等重入校验原快照，禁止用新内容覆写已发送结果。 */
    public void restaurantDecision(RestaurantDecisionNotification input) {
        requireTransaction();
        if (input == null
                || input.getRecipientUserId() <= 0
                || input.getRequestId() <= 0
                || !("CREATE".equals(input.getRequestType())
                        || "UPDATE".equals(input.getRequestType()))
                || !("APPROVED".equals(input.getDecision())
                        || "REJECTED".equals(input.getDecision()))
                || !length(input.getRestaurantName(), 120)
                || ("APPROVED".equals(input.getDecision())
                        ? input.getRejectReason() != null
                        : !length(input.getRejectReason(), 200))) {
            throw new IllegalArgumentException("Invalid restaurant decision notification");
        }
        var row = new NotificationEntity();
        row.setRecipientUserId(input.getRecipientUserId());
        row.setRequestId(input.getRequestId());
        row.setRequestType(input.getRequestType());
        row.setDecision(input.getDecision());
        row.setRestaurantName(input.getRestaurantName());
        row.setRejectReason(input.getRejectReason());
        messages.insert(row);
        var saved = messages.byRequest(input.getRecipientUserId(), input.getRequestId());
        if (saved == null
                || !Objects.equals(saved.getRequestType(), row.getRequestType())
                || !Objects.equals(saved.getDecision(), row.getDecision())
                || !Objects.equals(saved.getRestaurantName(), row.getRestaurantName())
                || !Objects.equals(saved.getRejectReason(), row.getRejectReason())) {
            throw new IllegalStateException(
                    "Notification source conflicts with its existing snapshot");
        }
    }

    /** 自己回复不发通知；同一来源幂等且核验作者/点评，不允许换收件人或覆盖既有事实。 */
    public void reviewReply(ReviewReplyNotification input) {
        requireTransaction();
        if (input == null
                || input.getRecipientUserId() <= 0
                || input.getActorUserId() <= 0
                || input.getReplyId() <= 0
                || input.getReviewId() <= 0) {
            throw new IllegalArgumentException("Invalid reply notification");
        }
        if (input.getActorUserId() == input.getRecipientUserId()) {
            return;
        }
        var row = new NotificationEntity();
        row.setRecipientUserId(input.getRecipientUserId());
        row.setSourceId(input.getReplyId());
        row.setReviewId(input.getReviewId());
        row.setActorUserId(input.getActorUserId());
        messages.insertReply(row);
        var saved = messages.byReply(input.getRecipientUserId(), input.getReplyId());
        if (saved == null
                || !Objects.equals(saved.getReviewId(), row.getReviewId())
                || !Objects.equals(saved.getActorUserId(), row.getActorUserId())) {
            throw new IllegalStateException("Reply notification source conflicts");
        }
    }

    /** 点赞事实只发布一次；失败参与外层点赞事务回滚，重入还需校验收件事实一致。 */
    public void contentLike(com.ginkgo.foodmap.notification.dto.ContentLikeNotification input) {
        requireTransaction();
        if (input == null
                || input.getRecipientUserId() <= 0
                || input.getActorUserId() <= 0
                || input.getLikeId() <= 0
                || input.getReviewId() <= 0
                || input.getRecipientUserId() == input.getActorUserId()
                || !java.util.Set.of("REVIEW", "REPLY").contains(input.getTargetType())) {
            throw new IllegalArgumentException("Invalid like notification");
        }
        var row = new NotificationEntity();
        row.setRecipientUserId(input.getRecipientUserId());
        row.setActorUserId(input.getActorUserId());
        row.setSourceId(input.getLikeId());
        row.setReviewId(input.getReviewId());
        row.setNotificationType(
                "REVIEW".equals(input.getTargetType()) ? "REVIEW_LIKED" : "REPLY_LIKED");
        messages.insertLike(row);
        var saved =
                messages.bySource(
                        row.getRecipientUserId(), row.getNotificationType(), row.getSourceId());
        if (saved == null
                || !Objects.equals(saved.getActorUserId(), row.getActorUserId())
                || !Objects.equals(saved.getReviewId(), row.getReviewId())) {
            throw new IllegalStateException("Like notification source conflicts");
        }
    }

    /** 首次关注与关系同事务写入，失败则回滚整笔关注，不在提交后异步发消息。 */
    public void follow(com.ginkgo.foodmap.notification.dto.FollowNotification input) {
        requireTransaction();
        if (input == null
                || input.getRecipientUserId() <= 0
                || input.getActorUserId() <= 0
                || input.getRelationId() <= 0
                || input.getRecipientUserId() == input.getActorUserId()) {
            throw new IllegalArgumentException("Invalid follow fact");
        }
        var row = new NotificationEntity();
        row.setRecipientUserId(input.getRecipientUserId());
        row.setActorUserId(input.getActorUserId());
        row.setSourceId(input.getRelationId());
        messages.insertFollow(row);
        var saved =
                messages.bySource(
                        input.getRecipientUserId(), "USER_FOLLOWED", input.getRelationId());
        if (saved == null || !Objects.equals(saved.getActorUserId(), input.getActorUserId())) {
            throw new IllegalStateException("Follow notification source conflict");
        }
    }

    /** 保留与审核/回复相同的物理事务，通知失败由业务入口结束整笔尝试后再重试。 */
    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
                        .equals(
                                TransactionSynchronizationManager
                                        .getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException(
                    "Notifications require a READ_COMMITTED write transaction");
        }
    }

    private boolean length(String value, int max) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max;
    }
}
