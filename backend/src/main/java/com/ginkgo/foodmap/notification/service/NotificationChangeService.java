package com.ginkgo.foodmap.notification.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 数据库为事实来源；事务提交后才广播，回滚不发送提示。 */
@Service
@RequiredArgsConstructor
public class NotificationChangeService {
    private final NotificationStreamService streams;

    /** 全体公告发布或撤回后提醒当前实例所有在线用户；不扫描用户表、不生成逐人消息。 */
    public void allChanged() {
        changed(0);
    }

    /** 同一事务按收件人合并提示，避免批量已读触发重复广播。 */
    public void changed(long owner) {
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Change change && change.owner == owner) {
                return;
            }
        }
        TransactionSynchronizationManager.registerSynchronization(new Change(owner));
    }

    private final class Change implements TransactionSynchronization {
        private final long owner;

        private Change(long owner) {
            this.owner = owner;
        }

        @Override
        public void afterCommit() {
            streams.changed(owner);
        }
    }
}
