package com.foodmap.restaurant.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.restaurant.cache.RestaurantRankingCache;
import com.foodmap.restaurant.config.RestaurantRankingProperties;
import com.foodmap.restaurant.entity.RestaurantRankingSnapshotEntity;
import com.foodmap.restaurant.exception.RestaurantErrorCode;
import com.foodmap.restaurant.mapper.RestaurantRankingSnapshotMapper;
import com.foodmap.restaurant.support.RestaurantRankingKey;
import com.foodmap.restaurant.support.RestaurantRankingSnapshotCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;

/**
 * 仅发布已经计算好的四榜，不包含聚合或调度，也不暴露HTTP写入。
 * 用编程事务保证实际提交早于缓存失效和解锁，拒绝嵌入外层长事务。
 */
@Service
@RequiredArgsConstructor
public class RestaurantRankingSnapshotPublishService {
    private final RestaurantRankingSnapshotMapper snapshots;
    private final RestaurantRankingCache cache;
    private final RestaurantRankingSnapshotCodec codec;
    private final RestaurantRankingProperties properties;
    private final Clock restaurantRankingClock;
    private final PlatformTransactionManager transactions;

    /** 验证及复制放锁外；整轮四行同一截止时间，任何写入失败全部回滚并保留旧缓存。 */
    public void publish(List<RestaurantRankingSnapshotEntity> computed) {
        if (computed == null || computed.size() != 4 || computed.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT, "发布必须包含同轮完整四份榜单");
        }
        var rows = computed.stream().map(source -> {
            var copy = new RestaurantRankingSnapshotEntity();
            BeanUtils.copyProperties(source, copy);
            return copy;
        }).toList();
        var keys = new HashSet<RestaurantRankingKey>();
        var cutoff = rows.getFirst().getWindowEndAt();
        for (var row : rows) {
            var key = new RestaurantRankingKey(row.getCampusCode(), row.getPeriodType());
            if (!keys.add(key) || cutoff == null || !cutoff.equals(row.getWindowEndAt())
                    || !"READY".equals(codec.view(row, key, restaurantRankingClock.instant(),
                            properties.getMaxSnapshotAge()).getStatus())) {
                throw new BusinessException(RestaurantErrorCode.INVALID_INPUT, "榜单发布结果不完整或已失效");
            }
        }
        cache.publishLocked(() -> {
            var transaction = new TransactionTemplate(transactions);
            transaction.setTimeout(5);
            transaction.executeWithoutResult(status -> {
                for (var row : rows) {
                    var old = snapshots.selectSnapshot(row.getCampusCode(), row.getPeriodType());
                    if (old != null && old.getWindowEndAt().isAfter(row.getWindowEndAt())) {
                        throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT, "较早榜单不能覆盖较新结果");
                    }
                    snapshots.upsertSnapshot(row);
                }
            });
            // TransactionTemplate已返回，四行真实提交完成；DEL失败只标记绕过，不撤销成功快照。
            cache.invalidateLocked(keys);
            return null;
        });
    }
}
