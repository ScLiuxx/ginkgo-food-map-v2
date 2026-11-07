package com.ginkgo.foodmap.restaurant.mapper;

import com.ginkgo.foodmap.restaurant.entity.RestaurantEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** 只读取restaurant自有表；批量返回未删除店铺，停业不等于不可访问。 */
@Mapper
public interface RestaurantReviewTargetMapper {
    @Select("""
        <script>
        SELECT id,name,business_status FROM restaurants
        WHERE deleted_at IS NULL AND id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<RestaurantEntity> visible(@Param("ids") List<Long> ids);
}
