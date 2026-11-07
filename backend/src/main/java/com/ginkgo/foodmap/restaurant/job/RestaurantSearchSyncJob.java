package com.ginkgo.foodmap.restaurant.job;

import com.ginkgo.foodmap.restaurant.service.RestaurantSearchSyncService;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只负责固定延迟调度；单实例串行处理，批次结束后再等待，默认不启用。 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.search.sync.enabled", havingValue = "true")
public class RestaurantSearchSyncJob {
    private final RestaurantSearchSyncService sync;

    /** 启动仅记录启用状态，不记录商户内容和连接信息。 */
    @PostConstruct
    public void enabled() {
        log.info("商户搜索Outbox单实例定时消费已启用");
    }

    @Scheduled(
            fixedDelayString = "${app.search.sync.delay-ms:5000}",
            initialDelayString = "${app.search.sync.delay-ms:5000}")
    public void run() {
        try {
            sync.processBatch();
        } catch (Exception failure) {
            log.warn("搜索任务扫描失败，type={}", failure.getClass().getSimpleName());
        }
    }
}
