package com.foodmap.statistics.service;

import com.foodmap.user.service.UserAccountService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 业务首次创建节点调用；只注册提交后回调，不把统计故障传回核心业务事务。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityContributionService {
    private final ActivityStatisticsService statistics;
    private final UserAccountService accounts;

    public void registered() {
        schedule(true, true);
    }

    /** 作者可能是待发布记录中的普通用户，不能用当前审核管理员的身份替代。 */
    public void reviewPublished(long authorUserId) {
        if (!statistics.isCollecting()) {
            return;
        }
        Boolean eligible;
        try {
            eligible =
                    accounts.findById(authorUserId)
                            .map(
                                    user ->
                                            user.getRoles().contains("ROLE_USER")
                                                    && !user.getRoles().contains("ROLE_ADMIN"))
                            .orElse(false);
        } catch (RuntimeException failure) {
            eligible = null;
        }
        schedule(false, eligible);
    }

    private void schedule(boolean registration, Boolean eligible) {
        if (!statistics.isCollecting()
                || Boolean.FALSE.equals(eligible)
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        var occurredAt = statistics.now();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            statistics.contribution(occurredAt, registration, eligible);
                        } catch (RuntimeException failure) {
                            log.warn(
                                    "统计回调失败，已提交业务保持成功，type={}", failure.getClass().getSimpleName());
                        }
                    }
                });
    }
}
