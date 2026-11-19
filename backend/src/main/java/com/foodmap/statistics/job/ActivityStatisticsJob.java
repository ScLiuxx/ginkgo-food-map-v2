package com.foodmap.statistics.job;

import com.foodmap.statistics.service.ActivityStatisticsService;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 单后台线程固定延迟运行；不占用ES/OSS调度器，不创建无限队列。 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.activity.enabled", havingValue = "true")
public class ActivityStatisticsJob {
    private final ActivityStatisticsService statistics;
    private final ScheduledThreadPoolExecutor scheduler = scheduler();
    private boolean started;

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started || scheduler.isShutdown()) {
            return;
        }
        started = true;
        scheduler.scheduleWithFixedDelay(this::run, 0, 60, TimeUnit.SECONDS);
    }

    public void run() {
        try {
            statistics.maintain();
        } catch (RuntimeException failure) {
            log.warn("统计维护失败，等待下次周期，type={}", failure.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException exception) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ScheduledThreadPoolExecutor scheduler() {
        var executor =
                new ScheduledThreadPoolExecutor(
                        1,
                        Thread.ofPlatform().name("activity-statistics-", 0).daemon(true).factory());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }
}
