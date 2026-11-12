package com.ginkgo.foodmap.restaurant.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单实例四榜生成编排：锁外计算完整结果，再用已有短事务发布。
 * 本服务不加事务，计算服务通过Spring代理结束独立只读事务后才进入发布。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RestaurantRankingGenerationService {
    private final RestaurantRankingCalculationService calculation;
    private final RestaurantRankingSnapshotPublishService publication;
    private final Clock restaurantRankingClock;
    // 与缓存锁职责不同：只合并并发生成请求，不阻塞普通读取，不排队积累过时任务。
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 返回true表示四榜已提交；已有生成任务时立即返回false。
     * 异常交给触发器记录，失败不清缓存；finally保证下一轮仍可执行。
     */
    public boolean generate() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("榜单生成不能嵌入外层事务");
        }
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            var cutoff = restaurantRankingClock.instant();
            long started = System.nanoTime();
            var computed = calculation.calculate(cutoff);
            publication.publish(computed);
            log.info("四份餐厅榜单已发布，cutoff={}，elapsedMs={}", cutoff,
                    (System.nanoTime() - started) / 1_000_000);
            return true;
        } finally {
            running.set(false);
        }
    }
}
