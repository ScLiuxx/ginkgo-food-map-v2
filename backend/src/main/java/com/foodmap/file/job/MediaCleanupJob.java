package com.foodmap.file.job;

import com.foodmap.file.service.MediaCleanupService;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时触发器只负责调度，状态转换与删除重试交给清理服务。 */
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${app.file.oss.enabled:false} or ${app.file.cos.enabled:false}")
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
