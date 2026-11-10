package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncProperties;
import com.ginkgo.foodmap.restaurant.event.RestaurantSearchOutboxRecorded;
import com.ginkgo.foodmap.restaurant.job.RestaurantSearchSyncTrigger;
import com.ginkgo.foodmap.restaurant.service.RestaurantSearchSyncService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 使用真实Spring事务事件阶段及专属线程验证提交边界、通知合并、尾部竞态和兜底恢复。 */
class RestaurantSearchSyncTriggerTest {
    @Test
    void onlyCommitWakesWorkerWithoutBlockingOrInheritingTransaction() throws Exception {
        var sync = mock(RestaurantSearchSyncService.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var active = new AtomicBoolean(true);
        var threadName = new AtomicReference<String>();
        when(sync.processBatch())
                .thenAnswer(
                        invocation -> {
                            active.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            threadName.set(Thread.currentThread().getName());
                            entered.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return false;
                        });
        try (var context = context(sync)) {
            try {
                var transaction =
                        new TransactionTemplate(context.getBean(TestTransactionManager.class));
                transaction.executeWithoutResult(
                        status -> {
                            context.publishEvent(new RestaurantSearchOutboxRecorded());
                            verifyNoInteractions(sync);
                        });
                // 返回时消费者仍被阻塞，证明提交线程没有等ES处理；子线程没有继承原事务。
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(release.getCount()).isEqualTo(1);
                assertThat(active.get()).isFalse();
                assertThat(threadName.get()).isEqualTo("restaurant-search-sync");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void rollbackAndNonTransactionalEventsDoNotWakeWorker() {
        var sync = mock(RestaurantSearchSyncService.class);
        try (var context = context(sync)) {
            var transaction =
                    new TransactionTemplate(context.getBean(TestTransactionManager.class));
            transaction.executeWithoutResult(
                    status -> {
                        context.publishEvent(new RestaurantSearchOutboxRecorded());
                        status.setRollbackOnly();
                    });
            context.publishEvent(new RestaurantSearchOutboxRecorded());
            verify(sync, after(100).never()).processBatch();
        }
    }

    @Test
    void notificationsDuringConsumptionAreCoalescedAndStillCauseAnotherScan() throws Exception {
        var sync = mock(RestaurantSearchSyncService.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            entered.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return false;
                        })
                .doReturn(false)
                .when(sync)
                .processBatch();
        var trigger = new RestaurantSearchSyncTrigger(sync, immediate());
        try {
            trigger.requestDrain();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 1000; i++) trigger.requestDrain();
            verify(sync, times(1)).processBatch();
            release.countDown();
            verify(sync, timeout(2000).times(2)).processBatch();
        } finally {
            release.countDown();
            trigger.close();
        }
    }

    @Test
    void fullBatchesDrainImmediatelyAcrossBoundedWorkerRuns() {
        var sync = mock(RestaurantSearchSyncService.class);
        var remaining = new java.util.concurrent.atomic.AtomicInteger(23);
        when(sync.processBatch()).thenAnswer(invocation -> remaining.decrementAndGet() > 0);
        var trigger = new RestaurantSearchSyncTrigger(sync, immediate());
        try {
            trigger.requestDrain();
            verify(sync, timeout(2000).times(23)).processBatch();
        } finally {
            trigger.close();
        }
    }

    @Test
    void scanErrorAndNoProgressWaitForNextWakeInsteadOfHotLoop() {
        var sync = mock(RestaurantSearchSyncService.class);
        when(sync.processBatch())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(false);
        var trigger = new RestaurantSearchSyncTrigger(sync, immediate());
        try {
            trigger.requestDrain();
            verify(sync, after(100).times(1)).processBatch();
            trigger.requestDrain();
            verify(sync, timeout(2000).times(2)).processBatch();
        } finally {
            trigger.close();
        }
    }

    @Test
    void closingWorkerDoesNotFailLaterNotifications() {
        var sync = mock(RestaurantSearchSyncService.class);
        var trigger = new RestaurantSearchSyncTrigger(sync, immediate());
        trigger.close();
        trigger.requestDrain();
        verifyNoInteractions(sync);
    }

    @Test
    void fixedWindowCollectsNotificationsWithoutPushingDeadline() throws Exception {
        var sync = mock(RestaurantSearchSyncService.class);
        var properties = new RestaurantSearchSyncProperties();
        properties.setCoalesceWindowMs(300);
        var first = new CountDownLatch(1);
        when(sync.processBatch())
                .thenAnswer(
                        invocation -> {
                            first.countDown();
                            return false;
                        });
        var trigger = new RestaurantSearchSyncTrigger(sync, properties);
        try {
            long started = System.nanoTime();
            trigger.requestDrain();
            verify(sync, after(100).never()).processBatch();
            // 后续通知持续越过窗口截止；消费者必须按首次通知的窗口开始工作，不能无限debounce。
            while (!first.await(10, TimeUnit.MILLISECONDS)
                    && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500) {
                trigger.requestDrain();
            }
            assertThat(first.getCount()).isZero();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .isGreaterThanOrEqualTo(250);
            verify(sync, times(1)).processBatch();
        } finally {
            trigger.close();
        }
    }

    @Test
    void closeDuringCollectionLeavesWorkForPersistentFallback() {
        var sync = mock(RestaurantSearchSyncService.class);
        var properties = new RestaurantSearchSyncProperties();
        properties.setCoalesceWindowMs(500);
        var trigger = new RestaurantSearchSyncTrigger(sync, properties);
        trigger.requestDrain();
        trigger.close();
        verifyNoInteractions(sync);
    }

    private RestaurantSearchSyncProperties immediate() {
        var properties = new RestaurantSearchSyncProperties();
        properties.setCoalesceWindowMs(0);
        return properties;
    }

    private AnnotationConfigApplicationContext context(RestaurantSearchSyncService sync) {
        var context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("app.search.sync.enabled=true").applyTo(context);
        context.registerBean(RestaurantSearchSyncService.class, () -> sync);
        context.registerBean(RestaurantSearchSyncProperties.class, this::immediate);
        context.register(TestConfiguration.class, RestaurantSearchSyncTrigger.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfiguration {
        @Bean
        TestTransactionManager transactionManager() {
            return new TestTransactionManager();
        }
    }

    /** 不访问外部库，只让Spring真实驱动提交/回滚及AFTER_COMMIT同步回调。 */
    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
