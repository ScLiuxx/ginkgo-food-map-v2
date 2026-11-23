package com.foodmap.restaurant.job;

import com.foodmap.restaurant.cache.RestaurantRankingCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 清理重启残留及有限失效重试；只删固定键，不生成榜单、不携带旧数据异步回填。 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.ranking.redis-enabled", havingValue = "true", matchIfMissing = true)
public class RestaurantRankingCacheMaintenanceJob {
    private final RestaurantRankingCache cache;

    /** 启动失败允许服务继续从MySQL读取，dirty门禁也覆盖就绪事件前的首次请求。 */
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        recover();
    }

    /** 每轮尝试次数和冷却由协调器限制，竞争锁失败留待下轮。 */
    @Scheduled(fixedDelayString = "${app.ranking.repair-delay:5s}",
            initialDelayString = "${app.ranking.repair-delay:5s}")
    public void recover() {
        try {
            cache.recover();
        } catch (RuntimeException exception) {
            log.warn("榜单缓存维护稍后重试，type={}", exception.getClass().getSimpleName());
        }
    }
}
