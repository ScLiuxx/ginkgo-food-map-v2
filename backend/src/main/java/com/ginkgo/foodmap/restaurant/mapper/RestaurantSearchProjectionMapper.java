package com.ginkgo.foodmap.restaurant.mapper;

import com.ginkgo.foodmap.restaurant.entity.RestaurantSearchSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** 仅访问restaurant模块所属表；批量读取含软删除的商户，供完整文档或删除墓碑组装。 */
@Mapper
public interface RestaurantSearchProjectionMapper {
    /** 显式列白名单；原始累计值留给组装规则计算，不复用页面展示分或触发统计初始化。 */
    @Select("""
        <script>
        SELECT r.id,r.search_projection_version AS projection_version,r.name,r.category_code,c.name AS category_name,r.restaurant_type,
          r.supports_dine_in,r.supports_delivery,r.price_per_person,r.price_kind,r.address,
          r.latitude,r.longitude,r.needs_location,r.business_status,(r.deleted_at IS NOT NULL) AS deleted,
          COALESCE(s.review_count,0) AS review_count,COALESCE(s.participant_count,0) AS participant_count,
          COALESCE(s.recommend_count,0) AS recommend_count,COALESCE(s.overall_score_sum,0) AS overall_score_sum
        FROM restaurants r LEFT JOIN restaurant_categories c ON c.code=r.category_code
        LEFT JOIN restaurant_review_statistics s ON s.restaurant_id=r.id
        WHERE r.id IN
        <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach>
        ORDER BY r.id
        </script>
        """)
    List<RestaurantSearchSource> selectSources(@Param("ids") List<Long> ids);
}
