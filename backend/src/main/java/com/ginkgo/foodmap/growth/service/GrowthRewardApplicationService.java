package com.ginkgo.foodmap.growth.service;

import com.ginkgo.foodmap.growth.config.GrowthProperties;
import com.ginkgo.foodmap.growth.dto.FirstStoreReviewContribution;
import com.ginkgo.foodmap.growth.dto.GrowthRewardOutcome;
import com.ginkgo.foodmap.growth.entity.GrowthRewardEntity;
import com.ginkgo.foodmap.growth.mapper.GrowthAccountMapper;
import com.ginkgo.foodmap.growth.mapper.GrowthRewardMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;

/** 成长公开协作入口；业务模块先验证并锁定来源，再在同一事务内计奖，不暴露HTTP写接口。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class GrowthRewardApplicationService {
    private final GrowthAccountMapper accounts;
    private final GrowthRewardMapper rewards;
    private final GrowthProperties properties;

    /** 首次审核成功后调用：新店按申请计奖，纠错按受益人和店铺保留永久资格。 */
    public GrowthRewardOutcome grantApprovedRequest(
            long user, long restaurant, long request, String requestType) {
        requireWriteTransaction();
        positive(user, restaurant, request);
        if (!"CREATE".equals(requestType) && !"UPDATE".equals(requestType)) {
            throw new IllegalArgumentException("Unknown approval contribution");
        }
        String type = "CREATE".equals(requestType) ? "NEW_STORE_APPROVED" : "CORRECTION_APPROVED";
        String key = Long.toString("CREATE".equals(requestType) ? request : restaurant);
        accounts.initialize(user);
        if (accounts.lock(user) == null) {
            throw new IllegalStateException("Growth account unavailable");
        }
        var existing = rewards.approval(user, type, key);
        if (existing != null) {
            return "REVOKED".equals(existing.getStatus())
                    ? GrowthRewardOutcome.ALREADY_REVOKED
                    : GrowthRewardOutcome.ALREADY_GRANTED;
        }
        int amount =
                "CREATE".equals(requestType)
                        ? properties.getNewStoreExperience()
                        : properties.getCorrectionExperience();
        if (amount <= 0) {
            throw new IllegalStateException("Invalid approval reward amount");
        }
        var row = new GrowthRewardEntity();
        row.setUserId(user);
        row.setRewardType(type);
        row.setBusinessKey(key);
        row.setSourceId(request);
        row.setAmount(amount);
        if (rewards.insertApproval(row) != 1 || accounts.increase(user, amount) != 1) {
            throw new IllegalStateException("Approval reward and account update inconsistent");
        }
        return GrowthRewardOutcome.GRANTED;
    }

    /** 每用户每店只奖励一次；原点评删除或变更请求UUID不会释放资格，重复调用不重算旧金额。 */
    public GrowthRewardOutcome grantFirstStoreReview(FirstStoreReviewContribution contribution) {
        requireWriteTransaction();
        if (contribution == null) {
            throw new IllegalArgumentException("Missing review contribution");
        }
        positive(
                contribution.getUserId(),
                contribution.getRestaurantId(),
                contribution.getReviewId());
        long user = contribution.getUserId();
        accounts.initialize(user);
        if (accounts.lock(user) == null) {
            throw new IllegalStateException("Growth account unavailable");
        }
        String key = Long.toString(contribution.getRestaurantId());
        var existing = rewards.firstStoreReview(user, key);
        if (existing != null) {
            return "REVOKED".equals(existing.getStatus())
                    ? GrowthRewardOutcome.ALREADY_REVOKED
                    : GrowthRewardOutcome.ALREADY_GRANTED;
        }
        int amount = properties.getFirstReviewExperience();
        if (amount <= 0) {
            throw new IllegalStateException("Invalid review reward amount");
        }
        var row = new GrowthRewardEntity();
        row.setUserId(user);
        row.setBusinessKey(key);
        row.setSourceId(contribution.getReviewId());
        row.setAmount(amount);
        if (rewards.insertFirstStoreReview(row) != 1 || accounts.increase(user, amount) != 1) {
            throw new IllegalStateException("Growth reward and account update inconsistent");
        }
        return GrowthRewardOutcome.GRANTED;
    }

    /** 首次点评获赞计奖：作者账户锁同时保护同点赞者次数与作者日总额，不使用跨存储预扣。 */
    public void grantReviewLike(long user, long actor, long review, java.time.Instant occurredAt) {
        requireWriteTransaction();
        positive(user, actor, review);
        if (user == actor || occurredAt == null) {
            throw new IllegalArgumentException("Invalid like reward");
        }
        accounts.initialize(user);
        if (accounts.lock(user) == null) {
            throw new IllegalStateException("Growth account unavailable");
        }
        String key = review + ":" + actor;
        if (rewards.approval(user, "REVIEW_LIKED", key) != null) {
            return;
        }
        var date = occurredAt.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
        int amount = properties.getLikeExperience();
        if (rewards.dailyActor(user, date, actor) > 0
                || rewards.dailyAmount(user, date)
                        > properties.getDailyLikeExperienceLimit() - amount) {
            return;
        }
        var row = new GrowthRewardEntity();
        row.setUserId(user);
        row.setActorUserId(actor);
        row.setSourceId(review);
        row.setBusinessKey(key);
        row.setAmount(amount);
        row.setBusinessDate(date);
        if (rewards.insertLike(row) != 1 || accounts.increase(user, amount) != 1) {
            throw new IllegalStateException("Like reward inconsistent");
        }
    }

    /** 内部风控协作能力：按原amount撤销一次；实际举报/处罚入口另行接入，普通删评不调用。 */
    public GrowthRewardOutcome revoke(long userId, long rewardId, long dispositionId) {
        requireWriteTransaction();
        positive(userId, rewardId, dispositionId);
        if (accounts.lock(userId) == null) {
            return GrowthRewardOutcome.NOT_FOUND;
        }
        var reward = rewards.find(userId, rewardId);
        if (reward == null) {
            return GrowthRewardOutcome.NOT_FOUND;
        }
        if ("REVOKED".equals(reward.getStatus())) {
            return GrowthRewardOutcome.ALREADY_REVOKED;
        }
        if (!"GRANTED".equals(reward.getStatus()) || reward.getAmount() <= 0) {
            throw new IllegalStateException("Invalid stored growth reward");
        }
        if (rewards.revoke(userId, rewardId, dispositionId) != 1
                || accounts.decrease(userId, reward.getAmount()) != 1) {
            throw new IllegalStateException("Growth revocation and account update inconsistent");
        }
        return GrowthRewardOutcome.REVOKED;
    }

    /** MANDATORY只保证有事务；显式拒绝只读或非READ_COMMITTED外层，避免未来额度检查读到旧快照。 */
    private void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
                        .equals(
                                TransactionSynchronizationManager
                                        .getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException(
                    "Growth writes require a READ_COMMITTED write transaction");
        }
    }

    private void positive(long... values) {
        for (long value : values) {
            if (value <= 0) {
                throw new IllegalArgumentException("Growth references must be positive");
            }
        }
    }
}
