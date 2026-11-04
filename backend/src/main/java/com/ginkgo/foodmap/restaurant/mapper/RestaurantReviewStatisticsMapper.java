package com.ginkgo.foodmap.restaurant.mapper;

import com.ginkgo.foodmap.restaurant.dto.RestaurantReviewDelta;
import org.apache.ibatis.annotations.*;

/** 只操作restaurant拥有的餐厅及统计表；所有调用由持有父餐厅锁的公开服务发起。 */
@Mapper
public interface RestaurantReviewStatisticsMapper {
    /** 不在锁语句中隐藏已删行，取锁后才能与并发删店形成明确先后顺序。 */
    @Select("SELECT id FROM restaurants WHERE id=#{id} FOR UPDATE")
    Long lockRestaurant(long id);

    /** 已持父锁，当前读取删除状态。停业不影响评价。 */
    @Select("SELECT COUNT(*) FROM restaurants WHERE id=#{id} AND deleted_at IS NULL")
    int visible(long id);

    /** 兼容旧版本服务在V5之后新建但尚未初始化统计的餐厅。 */
    @Insert("INSERT INTO restaurant_review_statistics(restaurant_id) VALUES(#{id}) ON DUPLICATE KEY UPDATE restaurant_id=restaurant_id")
    int initialize(long id);

    /** 一条原子UPDATE同时改变条数、人数与全部累计值，数据库CHECK约束兜底。 */
    @Update("""
        UPDATE restaurant_review_statistics SET review_count=review_count+#{delta.reviews},
          participant_count=participant_count+#{delta.participants},
          recommend_count=recommend_count+#{delta.recommendations},
          taste_sum=taste_sum+#{delta.taste}, value_sum=value_sum+#{delta.value},
          service_sum=service_sum+#{delta.service}, ambience_sum=ambience_sum+#{delta.ambience},
          overall_score_sum=overall_score_sum+#{delta.overall}
        WHERE restaurant_id=#{id}
        """)
    int applyDelta(@Param("id") long id, @Param("delta") RestaurantReviewDelta delta);

    /** 已持有商户行锁时调用；只推进搜索版本，显式保留资料审计时间。耗尽时回滚整个评价事务。 */
    @Update("""
        UPDATE restaurants SET search_projection_version=search_projection_version+1, updated_at=updated_at
        WHERE id=#{id} AND search_projection_version < 9223372036854775807
        """)
    int advanceSearchProjectionVersion(@Param("id") long id);
}
