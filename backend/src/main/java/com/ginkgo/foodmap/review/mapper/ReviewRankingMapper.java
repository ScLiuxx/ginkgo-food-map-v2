package com.ginkgo.foodmap.review.mapper;

import com.ginkgo.foodmap.review.dto.RestaurantPeriodReviewStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/** review独占近期计分查询；只访问reviews，不关联餐厅、账号或累计统计表。 */
@Mapper
public interface ReviewRankingMapper {
    /**
     * 先在半开区间[start,end)选点评，再按餐厅/用户取最新一条，最后聚合。
     * created_at相同按ID倒序打破平局；点评硬删除后自然回退，不借用周期外代表或回执。
     * 周月分别调用，各自独立去重；不提前按评分过滤，否则会错误回退到较高的旧点评。
     */
    @Select("""
        SELECT restaurant_id, COUNT(*) AS participant_count,
               SUM(taste_score) AS taste_sum, SUM(value_score) AS value_sum,
               SUM(service_score) AS service_sum, SUM(ambience_score) AS ambience_sum
        FROM (
            SELECT restaurant_id, taste_score, value_score, service_score, ambience_score,
                   ROW_NUMBER() OVER (PARTITION BY restaurant_id, author_user_id
                                      ORDER BY created_at DESC, id DESC) AS position_in_period
            FROM reviews
            WHERE restaurant_id IS NOT NULL AND window_id IS NULL
              AND created_at >= #{start} AND created_at < #{end}
        ) recent
        WHERE position_in_period = 1
        GROUP BY restaurant_id
        ORDER BY restaurant_id
        """)
    @Options(useCache = false, timeout = 30)
    List<RestaurantPeriodReviewStats> aggregate(@Param("start") Instant start, @Param("end") Instant end);
}
