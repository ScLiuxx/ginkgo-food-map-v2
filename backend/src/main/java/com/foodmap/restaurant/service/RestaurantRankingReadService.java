package com.foodmap.restaurant.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.restaurant.cache.RestaurantRankingCache;
import com.foodmap.restaurant.exception.RestaurantErrorCode;
import com.foodmap.restaurant.mapper.RestaurantRankingSnapshotMapper;
import com.foodmap.restaurant.support.RestaurantRankingKey;
import com.foodmap.restaurant.vo.RestaurantRankingVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

import java.time.DateTimeException;

/** Redis Cache Aside，未命中或故障读取MySQL快照，不聚合点评或逐店查询。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RestaurantRankingReadService {
    private final RestaurantRankingSnapshotMapper snapshots;
    private final RestaurantRankingCache cache;

    /**
     * 保持三种业务状态及安全503，关闭榜单Redis时可复跑MySQL读取基线。
     */
    public RestaurantRankingVo read(String campusCode, String periodType) {
        var key = new RestaurantRankingKey(campusCode, periodType);
        try {
            return cache.read(key, () -> snapshots.selectSnapshot(campusCode, periodType));
        } catch (DataAccessException | JacksonException | IllegalArgumentException | DateTimeException exception) {
            log.warn("榜单读取失败，type={}", exception.getClass().getSimpleName());
            throw new BusinessException(RestaurantErrorCode.RANKING_UNAVAILABLE);
        }
    }
}
