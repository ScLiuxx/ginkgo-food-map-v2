package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncProperties;
import com.ginkgo.foodmap.restaurant.entity.RestaurantSearchTask;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantSearchOutboxMapper;
import com.ginkgo.foodmap.restaurant.search.RestaurantSearchIndexWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单JVM串行消费，不支持多副本同时开启；ES请求不置于MySQL事务内，逐条故障隔离。 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.search.sync.enabled", havingValue = "true")
public class RestaurantSearchSyncService {
    private final RestaurantSearchOutboxMapper tasks;
    private final RestaurantSearchProjectionService projections;
    private final RestaurantSearchIndexWriter writer;
    private final RestaurantSearchSyncProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    /** 有界扫描；不持久标记PROCESSING，崩溃后任务仍PENDING，重放由ES版本及正文校验保护。 */
    @Transactional(propagation = Propagation.NEVER)
    public void processBatch() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (var task : tasks.selectDue(properties.getBatchSize(), properties.getMaxAttempts())) process(task);
        } finally { running.set(false); }
    }

    private void process(RestaurantSearchTask task) {
        try {
            var documents = projections.assemble(List.of(task.getRestaurantId()));
            if (documents.size() != 1 || documents.getFirst().getId() != task.getRestaurantId()
                    || documents.getFirst().getProjectionVersion() < task.getProjectionVersion())
                throw new IllegalStateException("投影身份或版本落后于任务");
            writer.write(documents.getFirst());
        } catch (Exception failure) {
            // 只记录错误类型；原始异常可能包含连接信息或整份商户正文，不写入数据库和日志。
            try {
                if (tasks.fail(task.getId(), task.getAttempts(), properties.getMaxAttempts(),
                        properties.getRetryDelayMs(), failure.getClass().getSimpleName()) != 1)
                    log.warn("搜索任务失败状态未更新，taskId={}", task.getId());
            } catch (Exception stateFailure) {
                log.warn("搜索任务失败状态保存异常，taskId={}，type={}", task.getId(), stateFailure.getClass().getSimpleName());
            }
            return;
        }
        // ES已经成功，确认失败时保留PENDING；下次重放核对相同版本，不错误消耗重试额度。
        try {
            if (tasks.succeed(task.getId(), task.getAttempts()) != 1)
                log.warn("搜索任务成功状态未更新，taskId={}", task.getId());
        } catch (Exception stateFailure) {
            log.warn("搜索任务成功状态保存异常，taskId={}，type={}", task.getId(), stateFailure.getClass().getSimpleName());
        }
    }
}
