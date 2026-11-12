package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.review.dto.RestaurantPeriodReviewStats;
import com.ginkgo.foodmap.review.mapper.ReviewRankingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 供榜单计算调用的内部应用服务；餐厅模块只调用该服务，不能访问review持久化细节。 */
@Service
@RequiredArgsConstructor
public class ReviewRankingService {
    private final ReviewRankingMapper reviews;

    /** 在调用方同一只读快照中聚合，首期最多30天；失败向上传播，不伪装成无人点评。 */
    public List<RestaurantPeriodReviewStats> aggregate(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end)
                || Duration.between(start, end).compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("近期点评统计区间须大于零且不超过30天");
        }
        return reviews.aggregate(start, end);
    }
}
