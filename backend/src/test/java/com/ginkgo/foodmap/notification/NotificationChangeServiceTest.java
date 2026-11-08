package com.ginkgo.foodmap.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ginkgo.foodmap.notification.service.NotificationChangeService;
import com.ginkgo.foodmap.notification.service.NotificationStreamService;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 验证提交、回滚与按收件人合并，不向开发数据库写入测试资料。 */
class NotificationChangeServiceTest {
    @Test
    void commitCoalescesByRecipient() {
        var streams = mock(NotificationStreamService.class);
        var changes = new NotificationChangeService(streams);
        TransactionSynchronizationManager.initSynchronization();
        try {
            changes.changed(11);
            changes.changed(11);
            changes.changed(22);
            verifyNoInteractions(streams);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(2);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
            verify(streams).changed(11);
            verify(streams).changed(22);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 全体提示同样必须经过提交边界，重复请求合并一次。 */
    @Test
    void globalChangesCoalesceAndRollbackDoesNotBroadcast() {
        var streams = mock(NotificationStreamService.class);
        var changes = new NotificationChangeService(streams);
        TransactionSynchronizationManager.initSynchronization();
        try {
            changes.allChanged();
            changes.allChanged();
            verifyNoInteractions(streams);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(1));
            verifyNoInteractions(streams);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackNeverPublishes() {
        var streams = mock(NotificationStreamService.class);
        TransactionSynchronizationManager.initSynchronization();
        try {
            new NotificationChangeService(streams).changed(11);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(1));
            verifyNoInteractions(streams);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
