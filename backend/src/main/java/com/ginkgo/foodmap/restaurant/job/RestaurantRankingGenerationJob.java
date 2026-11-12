package com.ginkgo.foodmap.restaurant.job;

import com.ginkgo.foodmap.restaurant.config.RestaurantRankingProperties;
import com.ginkgo.foodmap.restaurant.service.RestaurantRankingGenerationService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 服务就绪后异步生成，随后按完成时间固定间隔刷新；失败留旧榜，下一轮重试。
 * 独立调度器由本组件管理，不注册全局TaskScheduler，避免改变ES同步和文件清理的调度装配。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.ranking.generation-enabled", havingValue = "true", matchIfMissing = true)
public class RestaurantRankingGenerationJob {
    private final RestaurantRankingGenerationService generation;
    private final RestaurantRankingProperties properties;
    private final ScheduledThreadPoolExecutor scheduler = scheduler();
    private boolean started;

    /** 就绪事件只注册任务，不在启动线程扫描点评；重复事件不重复注册。 */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started || scheduler.isShutdown()) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::refresh, 0, properties.getRefreshInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        started = true;
    }

    /** RuntimeException必须在周期任务内处理，否则JDK会取消后续所有轮次。 */
    private void refresh() {
        try {
            if (!generation.generate()) {
                log.debug("餐厅榜单已有生成任务，本轮跳过");
            }
        } catch (RuntimeException exception) {
            // 不输出点评、SQL或凭据；失败保持持久快照及其原始时间，不伪装成功空榜。
            log.warn("餐厅榜单生成失败，保留旧榜并等待下一轮，type={}", exception.getClass().getSimpleName());
        }
    }

    /** 停止后不接新轮次，优先让当前60秒计算及5秒发布完成，再有界等待强制终止。 */
    @PreDestroy
    public synchronized void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(70, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException exception) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ScheduledThreadPoolExecutor scheduler() {
        var executor = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("restaurant-ranking-generation-", 0).daemon(true).factory());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }
}
