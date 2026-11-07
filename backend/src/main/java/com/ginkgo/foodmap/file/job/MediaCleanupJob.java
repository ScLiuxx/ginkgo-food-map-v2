package com.ginkgo.foodmap.file.job;

import com.ginkgo.foodmap.file.service.MediaCleanupService;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时触发器只负责调度，状态转换与删除重试交给清理服务。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.file.oss.enabled", havingValue = "true")
public class MediaCleanupJob {
    private final MediaCleanupService cleanup;

    /** 固定延迟避免单实例重叠，重复执行安全由清理服务保证。 */
    @Scheduled(
            fixedDelayString = "${app.file.oss.cleanup-delay-ms:60000}",
            initialDelayString = "${app.file.oss.cleanup-delay-ms:60000}")
    public void cleanup() {
        cleanup.cleanupExpired();
    }
}
