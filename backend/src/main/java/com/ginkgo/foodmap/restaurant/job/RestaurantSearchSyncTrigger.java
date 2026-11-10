package com.ginkgo.foodmap.restaurant.job;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncProperties;
import com.ginkgo.foodmap.restaurant.event.RestaurantSearchOutboxRecorded;
import com.ginkgo.foodmap.restaurant.service.RestaurantSearchSyncService;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 提交后通知与定时扫描共用单工作线程；通知合并为一个标志，不按任务创建线程或无限排队。 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.search.sync.enabled", havingValue = "true")
public class RestaurantSearchSyncTrigger {
    private static final int MAX_BATCHES_PER_RUN = 10;
    private final RestaurantSearchSyncService sync;
    private final RestaurantSearchSyncProperties properties;
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    runnable -> {
                        var thread = new Thread(runnable, "restaurant-search-sync");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    /** 默认不处理无事务事件；提交线程只置位并提交内存工作，不执行SQL或ES请求。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(RestaurantSearchOutboxRecorded event) {
        requestDrain();
    }

    /** 通知丢失、关闭或执行器拒绝均不回滚已提交业务；持久任务由下一次定时扫描恢复。 */
    public void requestDrain() {
        if (closed.get()) {
            return;
        }
        requested.set(true);
        schedule(true);
    }

    private void schedule(boolean collect) {
        if (closed.get() || !scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(() -> drain(collect));
        } catch (RejectedExecutionException rejected) {
            scheduled.set(false);
            log.warn("搜索同步唤醒未调度，等待定时兜底");
        }
    }

    private void drain(boolean collect) {
        try {
            if (collect) {
                // 只有后台线程等待固定窗口；通知不重新计时，业务提交线程立即返回。
                TimeUnit.MILLISECONDS.sleep(properties.getCoalesceWindowMs());
            }
            for (int batch = 0; batch < MAX_BATCHES_PER_RUN && !closed.get(); batch++) {
                requested.set(false);
                boolean full = sync.processBatch();
                if (full) {
                    requested.set(true);
                }
                if (!requested.get()) {
                    break;
                }
                if (!full) {
                    // 未满批但又有新提交时再给一个固定窗口；满批积压直接继续。
                    TimeUnit.MILLISECONDS.sleep(properties.getCoalesceWindowMs());
                }
            }
        } catch (InterruptedException interrupted) {
            requested.set(false);
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            // 任务仍持久保存；不无限快速重试数据库扫描，不把异常传回已经提交的HTTP请求。
            log.warn("搜索同步批次异常，等待后续唤醒或定时兜底，type={}", failure.getClass().getSimpleName());
        } finally {
            scheduled.set(false);
            // 交接时再次检查，避免消费过程中或退出临界区到来的通知被丢掉。
            if (requested.get()) {
                schedule(false);
            }
        }
    }

    /** 关闭后不再接受通知；限时等当前批次，未确认任务仍可由下次进程重放。 */
    @PreDestroy
    public void close() {
        closed.set(true);
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
