package com.ginkgo.foodmap.restaurant.job;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时兜底只唤醒同一个后台消费者；提交后通知丢失或进程重启后仍能恢复任务。 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.search.sync.enabled", havingValue = "true")
public class RestaurantSearchSyncJob {
    private final RestaurantSearchSyncTrigger trigger;

    /** 启动仅记录启用状态，不记录商户内容和连接信息。 */
    @PostConstruct
    public void enabled() {
        log.info("商户搜索Outbox提交后异步唤醒与定时兜底已启用");
    }

    @Scheduled(
            fixedDelayString = "${app.search.sync.delay-ms:30000}",
            initialDelayString = "${app.search.sync.delay-ms:30000}")
    public void run() {
        try {
            trigger.requestDrain();
        } catch (Exception failure) {
            log.warn("搜索任务扫描失败，type={}", failure.getClass().getSimpleName());
        }
    }
}
