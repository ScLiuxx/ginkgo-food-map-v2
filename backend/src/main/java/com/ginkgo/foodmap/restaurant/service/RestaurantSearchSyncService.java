package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncProperties;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchDocument;
import com.ginkgo.foodmap.restaurant.entity.RestaurantSearchTask;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantSearchOutboxMapper;
import com.ginkgo.foodmap.restaurant.metrics.RestaurantSearchSyncMetrics;
import com.ginkgo.foodmap.restaurant.search.RestaurantSearchIndexWriter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单JVM串行消费，不支持多副本同时开启；批内按餐厅合并写入，ES请求不置于MySQL事务内。 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.search.sync.enabled", havingValue = "true")
public class RestaurantSearchSyncService {
    private final RestaurantSearchOutboxMapper tasks;
    private final RestaurantSearchProjectionService projections;
    private final RestaurantSearchIndexWriter writer;
    private final RestaurantSearchSyncProperties properties;
    private final RestaurantSearchSyncMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean();

    /** 取一批到期任务后按餐厅合并，仅确认本批捕获的任务ID。 返回true表示满批且至少持久推进了一条任务，后台可立即继续；无进展时等待兜底，避免确认失败热循环。 */
    @Transactional(propagation = Propagation.NEVER)
    public boolean processBatch() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        boolean completed = false;
        try {
            var selected = tasks.selectDue(properties.getBatchSize(), properties.getMaxAttempts());
            Map<Long, List<RestaurantSearchTask>> groups = new LinkedHashMap<>();
            for (var task : selected) {
                groups.computeIfAbsent(task.getRestaurantId(), ignored -> new ArrayList<>())
                        .add(task);
            }
            boolean progressed = processGroups(groups);
            completed = true;
            return selected.size() == properties.getBatchSize() && progressed;
        } finally {
            running.set(false);
            metrics.batch(completed);
        }
    }

    private boolean processGroups(Map<Long, List<RestaurantSearchTask>> groups) {
        Map<Long, Long> started = new LinkedHashMap<>();
        Map<Long, Exception> failures = new LinkedHashMap<>();
        List<RestaurantSearchDocument> documents = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            long restaurantId = entry.getKey();
            started.put(restaurantId, System.nanoTime());
            metrics.group(entry.getValue().size());
            long requiredVersion =
                    entry.getValue().stream()
                            .mapToLong(RestaurantSearchTask::getProjectionVersion)
                            .max()
                            .orElseThrow();
            try {
                // 暂保留逐餐厅快照读取，避免一家来源异常让其他健康餐厅无法进入Bulk。
                var assembled = projections.assemble(List.of(restaurantId));
                if (assembled.size() != 1
                        || assembled.getFirst().getId() != restaurantId
                        || assembled.getFirst().getProjectionVersion() < requiredVersion) {
                    throw new IllegalStateException("投影身份或版本落后于本批任务");
                }
                documents.add(assembled.getFirst());
            } catch (Exception failure) {
                failures.put(restaurantId, failure);
            }
        }
        Map<Long, RestaurantSearchIndexWriter.WriteResult> results = Map.of();
        if (!documents.isEmpty()) {
            try {
                results = writer.writeBatch(documents);
            } catch (Exception failure) {
                // 防御写入器意外整体抛错；不把结果未知的文档当作成功，仍靠外部版本安全重放。
                for (var document : documents) {
                    failures.put(document.getId(), failure);
                }
            }
        }
        boolean progressed = false;
        for (var entry : groups.entrySet()) {
            long restaurantId = entry.getKey();
            Exception failure = failures.get(restaurantId);
            if (failure == null) {
                var result = results == null ? null : results.get(restaurantId);
                failure =
                        result == null
                                ? new IllegalStateException("Bulk未返回对应餐厅的确定结果")
                                : result.failure();
            }
            for (var task : entry.getValue()) {
                if (failure != null) {
                    // 共享写入失败仍保留每条任务各自的失败预算和重试时间。
                    progressed = fail(task, failure) || progressed;
                    metrics.task("write_error", task.getAttempts() > 0, started.get(restaurantId));
                } else {
                    // 只确认捕获的任务ID；ES成功但确认失败不能消耗写入失败预算。
                    boolean confirmed = acknowledge(task);
                    progressed = confirmed || progressed;
                    metrics.task(
                            confirmed ? "success" : "ack_error",
                            task.getAttempts() > 0,
                            started.get(restaurantId));
                }
            }
        }
        return progressed;
    }

    private boolean fail(RestaurantSearchTask task, Exception failure) {
        // 共享写入失败仍逐任务保存各自的重试次数；只记录脱敏错误类型。
        try {
            if (tasks.fail(
                            task.getId(),
                            task.getAttempts(),
                            properties.getMaxAttempts(),
                            properties.getRetryDelayMs(),
                            failure.getClass().getSimpleName())
                    == 1) {
                return true;
            }
            metrics.stateUpdateFailure(false);
            log.warn("搜索任务失败状态未更新，taskId={}", task.getId());
        } catch (Exception stateFailure) {
            metrics.stateUpdateFailure(false);
            log.warn(
                    "搜索任务失败状态保存异常，taskId={}，type={}",
                    task.getId(),
                    stateFailure.getClass().getSimpleName());
        }
        return false;
    }

    private boolean acknowledge(RestaurantSearchTask task) {
        // ES已成功，确认异常只保留PENDING；不能转调fail而错误消耗写入失败预算。
        try {
            if (tasks.succeed(task.getId(), task.getAttempts()) == 1) {
                return true;
            }
            metrics.stateUpdateFailure(true);
            log.warn("搜索任务成功状态未更新，taskId={}", task.getId());
        } catch (Exception stateFailure) {
            metrics.stateUpdateFailure(true);
            log.warn(
                    "搜索任务成功状态保存异常，taskId={}，type={}",
                    task.getId(),
                    stateFailure.getClass().getSimpleName());
        }
        return false;
    }
}
