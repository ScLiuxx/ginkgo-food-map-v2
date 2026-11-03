package com.ginkgo.foodmap.restaurant.mapper;

import com.ginkgo.foodmap.restaurant.dto.RestaurantRequestQuery;
import com.ginkgo.foodmap.restaurant.entity.RestaurantChangeEntity;
import com.ginkgo.foodmap.restaurant.entity.RestaurantRequestListEntity;
import com.ginkgo.foodmap.restaurant.vo.RestaurantRequestViews;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 仅访问restaurant模块的申请和图片关系；不跨模块JOIN账号或文件表。 */
@Mapper
public interface RestaurantRequestReadMapper {
    /** 按状态/关键字过滤后稳定排序分页，只返回管理列表必需列。 */
    List<RestaurantRequestListEntity> page(@Param("query") RestaurantRequestQuery query, @Param("offset") long offset);
    /** 与分页复用关键字条件，统计三个状态，供标签数量和当前状态总页数使用。 */
    List<RestaurantRequestViews.StatusCount> counts(@Param("query") RestaurantRequestQuery query);
    /** 私有读取允许访问已处理申请，不加写锁、不更新记录。 */
    @Select("SELECT * FROM restaurant_change_requests WHERE id=#{id}")
    RestaurantChangeEntity detail(long id);
    /** 只返回申请内的有序位置，不向HTTP调用者暴露存储键。 */
    @Select("SELECT position FROM restaurant_change_photos WHERE request_id=#{id} ORDER BY position")
    List<Integer> positions(long id);
    /** 图片读取必须从已验证申请关系解析文件ID，不能接收任意文件ID绕过关联。 */
    @Select("SELECT media_file_id FROM restaurant_change_photos WHERE request_id=#{id} AND position=#{position}")
    Long photo(@Param("id") long id, @Param("position") int position);
}
