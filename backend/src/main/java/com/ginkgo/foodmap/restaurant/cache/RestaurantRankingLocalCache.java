package com.ginkgo.foodmap.restaurant.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ginkgo.foodmap.restaurant.config.RestaurantRankingProperties;
import com.ginkgo.foodmap.restaurant.support.RestaurantRankingKey;
import com.ginkgo.foodmap.restaurant.vo.RestaurantRankingVo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 最多四份已解析榜单的本机缓存。内部记录及条目不可变，返回独立VO，防止调用者修改共享结果。
 * 写入/失效由协调器在同一把加载发布锁内调用；热读取无需拿锁，不访问Redis或解析JSON。
 */
@Component
public class RestaurantRankingLocalCache {
    private final RestaurantRankingProperties properties;
    private final Clock clock;
    private final Cache<RestaurantRankingKey, Entry> entries;

    /** Caffeine用单调时钟执行写后过期，另按业务时钟逐次检查数据年龄与准备中有效期。 */
    public RestaurantRankingLocalCache(RestaurantRankingProperties properties, Clock restaurantRankingClock) {
        this.properties = properties;
        this.clock = restaurantRankingClock;
        this.entries = Caffeine.newBuilder().maximumSize(RestaurantRankingKey.ALL.size())
                .expireAfterWrite(properties.getCaffeineTtl()).build();
    }

    /** 命中也检查原统计截止时间；比较删除防止旧读者误删发布后刚加载的新条目。 */
    public RestaurantRankingVo get(RestaurantRankingKey key) {
        if (!properties.isCaffeineEnabled()) {
            return null;
        }
        var entry = entries.getIfPresent(key);
        if (entry == null) {
            return null;
        }
        var now = clock.instant();
        boolean expired = now.isBefore(entry.cachedAt()) || !now.isBefore(entry.expiresAt())
                || (entry.windowEndAt() != null
                    && now.isAfter(entry.windowEndAt().plus(properties.getMaxSnapshotAge())));
        if (expired) {
            entries.asMap().remove(key, entry);
            return null;
        }
        return entry.toView(key);
    }

    /**
     * 只缓存已通过校验的READY/PREPARING，不缓存故障或超龄状态。
     * Redis准备中标记沿用原过期时刻，不因向本机回填再延长5秒。
     */
    public void put(RestaurantRankingKey key, RestaurantRankingVo view, Instant preparingExpiresAt) {
        if (!properties.isCaffeineEnabled() || "UNAVAILABLE".equals(view.getStatus())) {
            return;
        }
        var now = clock.instant();
        if (view.getWindowEndAt() != null
                && !now.isBefore(view.getWindowEndAt().plus(properties.getMaxSnapshotAge()))) {
            return;
        }
        var expiresAt = now.plus(properties.getCaffeineTtl());
        if ("PREPARING".equals(view.getStatus())) {
            var preparingEnd = preparingExpiresAt == null ? now.plus(properties.getPreparingTtl()) : preparingExpiresAt;
            if (preparingEnd.isBefore(expiresAt)) {
                expiresAt = preparingEnd;
            }
        }
        if (!now.isBefore(expiresAt)) {
            return;
        }
        var items = view.getItems().stream().map(item -> new Item(item.getRank(), item.getRestaurantId(),
                item.getName(), item.getCoverUrl(), item.getTasteScore(), item.getRecommendationScore(),
                item.getParticipantCount())).toList();
        entries.put(key, new Entry(view.getStatus(), view.getWindowStartAt(), view.getWindowEndAt(),
                view.getGeneratedAt(), now, expiresAt, items));
    }

    /** 提交后先清本机，再尝试DEL；即使Redis关闭或删除失败也不能继续展示旧本机结果。 */
    public void invalidate(Collection<RestaurantRankingKey> keys) {
        entries.invalidateAll(keys);
    }

    private record Entry(String status, Instant windowStartAt, Instant windowEndAt, Instant generatedAt,
                         Instant cachedAt, Instant expiresAt, List<Item> items) {
        private RestaurantRankingVo toView(RestaurantRankingKey key) {
            var view = new RestaurantRankingVo();
            view.setCampusCode(key.campusCode());
            view.setPeriodType(key.periodType());
            view.setStatus(status);
            view.setWindowStartAt(windowStartAt);
            view.setWindowEndAt(windowEndAt);
            view.setGeneratedAt(generatedAt);
            view.setItems(items.stream().map(Item::toView).toList());
            return view;
        }
    }

    private record Item(int rank, String restaurantId, String name, String coverUrl,
                        BigDecimal tasteScore, BigDecimal recommendationScore, long participantCount) {
        private RestaurantRankingVo.Item toView() {
            var item = new RestaurantRankingVo.Item();
            item.setRank(rank);
            item.setRestaurantId(restaurantId);
            item.setName(name);
            item.setCoverUrl(coverUrl);
            item.setTasteScore(tasteScore);
            item.setRecommendationScore(recommendationScore);
            item.setParticipantCount(participantCount);
            return item;
        }
    }
}
