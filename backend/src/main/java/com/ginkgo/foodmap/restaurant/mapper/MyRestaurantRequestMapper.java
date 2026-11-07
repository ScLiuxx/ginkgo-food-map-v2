package com.ginkgo.foodmap.restaurant.mapper;

import com.ginkgo.foodmap.restaurant.dto.MyRestaurantRequestQuery;
import com.ginkgo.foodmap.restaurant.entity.MyRestaurantRequestEntity;
import com.ginkgo.foodmap.restaurant.vo.MyRestaurantRequestViews;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 所有申请查询均在SQL中限定submitted_by，不靠内存过滤管理员结果；仅访问restaurant自有表。 */
@Mapper
public interface MyRestaurantRequestMapper {
    List<MyRestaurantRequestEntity> page(@Param("owner") long owner,
            @Param("query") MyRestaurantRequestQuery query, @Param("offset") long offset);

    @Select("SELECT status,COUNT(*) AS total FROM restaurant_change_requests WHERE submitted_by=#{owner} GROUP BY status")
    List<MyRestaurantRequestViews.StatusCount> counts(long owner);

    @Select("""
        SELECT id,restaurant_id,request_type,status,created_at,reviewed_at,proposed_data,approved_data,reject_reason
        FROM restaurant_change_requests WHERE id=#{id} AND submitted_by=#{owner}
        """)
    MyRestaurantRequestEntity detail(@Param("owner") long owner, @Param("id") long id);

    @Select("""
        SELECT p.position FROM restaurant_change_photos p
        JOIN restaurant_change_requests r ON r.id=p.request_id
        WHERE r.id=#{id} AND r.submitted_by=#{owner} ORDER BY p.position
        """)
    List<Integer> positions(@Param("owner") long owner, @Param("id") long id);

    @Select("""
        SELECT p.media_file_id FROM restaurant_change_photos p
        JOIN restaurant_change_requests r ON r.id=p.request_id
        WHERE r.id=#{id} AND r.submitted_by=#{owner} AND p.position=#{position}
        """)
    Long photo(@Param("owner") long owner, @Param("id") long id, @Param("position") int position);

    @Select("SELECT EXISTS(SELECT 1 FROM restaurants WHERE id=#{id} AND deleted_at IS NULL)")
    boolean targetAvailable(long id);
}
