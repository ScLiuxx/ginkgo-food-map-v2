package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.review.exception.ReplyErrorCode;
import com.ginkgo.foodmap.review.mapper.ReplyDeleteMapper;
import com.ginkgo.foodmap.review.mapper.ReplyMapper;
import com.ginkgo.foodmap.review.policy.ReplyPolicy;
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

/** 本人回复硬删除；与发布、点赞及整条删评共用父锁，不修改评分或成长经验。 */
@Service
@RequiredArgsConstructor
public class ReplyDeleteService {
    private final ReviewParentService parents;
    private final ReplyMapper replies;
    private final ReplyDeleteMapper deletion;
    private final CurrentActorService actors;
    private final PlatformTransactionManager transactions;

    /** 身份只取安全上下文；普通用户只能删除自己的回复。 */
    @PreAuthorize("isAuthenticated()")
    public void remove(String value) {
        long replyId = ReplyPolicy.reviewId(value);
        delete(replyId, actors.requireActor().getUserId());
    }

    /**
     * 管理员处置违规回复：跳过作者归属校验，沿用父点评锁和回执防重放。
     *
     * <p>返回true表示本次删除，false表示此前已删除；权限由管理安全链和方法注解双重校验。
     */
    @PreAuthorize("hasRole('ADMIN')")
    public boolean removeForModeration(long replyId) {
        return delete(replyId, null);
    }

    /** authorId为null表示管理员处置；事务边界、隔离级别与作者删除保持一致。 */
    private boolean delete(long replyId, Long authorId) {
        for (int attempt = 0; ; attempt++) {
            var transaction = new TransactionTemplate(transactions);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            transaction.setTimeout(10);
            try {
                return Boolean.TRUE.equals(
                        transaction.execute(status -> removeTransaction(replyId, authorId)));
            } catch (PessimisticLockingFailureException
                    | QueryTimeoutException
                    | TransactionTimedOutException failure) {
                if (attempt == 2) {
                    throw new BusinessException(ReplyErrorCode.BUSY);
                }
            }
        }
    }

    private boolean removeTransaction(long replyId, Long authorId) {
        var reply = replies.find(replyId);
        if (reply == null) {
            requireDeletedOwner(replyId, authorId);
            return false;
        }
        if (parents.lock(reply.getReviewId()) == null) {
            requireDeletedOwner(replyId, authorId);
            return false;
        }
        reply = replies.find(replyId);
        if (reply == null) {
            requireDeletedOwner(replyId, authorId);
            return false;
        }
        if (authorId != null && reply.getAuthorUserId() != authorId.longValue()) {
            throw new BusinessException(ReplyErrorCode.FORBIDDEN);
        }
        if (deletion.tombstone(replyId) != 1) {
            throw new IllegalStateException("Missing reply or request receipt during delete");
        }
        int removed =
                (authorId == null
                        ? deletion.removeAny(replyId)
                        : deletion.remove(replyId, authorId));
        if (removed != 1) {
            throw new IllegalStateException("Missing reply during delete");
        }
        // 有效获赞和通知目标读取均关联源回复；正文移除后自然排除获赞并返回内容失效。
        return true;
    }

    private void requireDeletedOwner(long replyId, Long authorId) {
        Long deletedOwner = deletion.deletedOwner(replyId);
        if (deletedOwner == null) {
            throw new BusinessException(ReplyErrorCode.REPLY_NOT_FOUND);
        }
        if (authorId != null && deletedOwner.longValue() != authorId) {
            throw new BusinessException(ReplyErrorCode.FORBIDDEN);
        }
    }
}
