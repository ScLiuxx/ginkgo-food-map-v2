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

    /** 身份只取安全上下文，管理员与点评作者均不具备代删他人回复的权限。 */
    @PreAuthorize("isAuthenticated()")
    public void remove(String value) {
        long replyId = ReplyPolicy.reviewId(value);
        long authorId = actors.requireActor().getUserId();
        for (int attempt = 0; attempt < 3; attempt++) {
            var transaction = new TransactionTemplate(transactions);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            transaction.setTimeout(10);
            try {
                transaction.executeWithoutResult(status -> removeTransaction(replyId, authorId));
                return;
            } catch (PessimisticLockingFailureException
                    | QueryTimeoutException
                    | TransactionTimedOutException failure) {
                if (attempt == 2) {
                    throw new BusinessException(ReplyErrorCode.BUSY);
                }
            }
        }
    }

    private void removeTransaction(long replyId, long authorId) {
        var reply = replies.find(replyId);
        if (reply == null) {
            requireDeletedOwner(replyId, authorId);
            return;
        }
        if (parents.lock(reply.getReviewId()) == null) {
            requireDeletedOwner(replyId, authorId);
            return;
        }
        reply = replies.find(replyId);
        if (reply == null) {
            requireDeletedOwner(replyId, authorId);
            return;
        }
        if (reply.getAuthorUserId() != authorId) {
            throw new BusinessException(ReplyErrorCode.FORBIDDEN);
        }
        if (deletion.tombstone(replyId) != 1 || deletion.remove(replyId, authorId) != 1) {
            throw new IllegalStateException("Missing reply or request receipt during delete");
        }
        // 有效获赞和通知目标读取均关联源回复；正文移除后自然排除获赞并返回内容失效。
    }

    private void requireDeletedOwner(long replyId, long authorId) {
        Long deletedOwner = deletion.deletedOwner(replyId);
        if (deletedOwner == null) {
            throw new BusinessException(ReplyErrorCode.REPLY_NOT_FOUND);
        }
        if (deletedOwner != authorId) {
            throw new BusinessException(ReplyErrorCode.FORBIDDEN);
        }
    }
}
