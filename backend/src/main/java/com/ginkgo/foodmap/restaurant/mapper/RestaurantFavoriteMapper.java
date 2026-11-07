package com.ginkgo.foodmap.restaurant.mapper;
import com.ginkgo.foodmap.restaurant.vo.FavoriteViews;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** 只访问restaurant模块自有表，权限由用户模块公开服务判定后传入可见范围。 */
@Mapper
public interface RestaurantFavoriteMapper {
    @Select("SELECT EXISTS(SELECT 1 FROM restaurant_favorites WHERE user_id=#{user} AND restaurant_id=#{restaurant})")
    boolean contains(@Param("user") long user,@Param("restaurant") long restaurant);
    /** 唯一键处理重复收藏，保留原收藏时间和顺序，不触发消息或经验。 */
    @Insert("INSERT INTO restaurant_favorites(user_id,restaurant_id) VALUES(#{user},#{restaurant}) ON DUPLICATE KEY UPDATE id=restaurant_favorites.id")
    void add(@Param("user") long user,@Param("restaurant") long restaurant);
    @Delete("DELETE FROM restaurant_favorites WHERE user_id=#{user} AND restaurant_id=#{restaurant}")
    void remove(@Param("user") long user,@Param("restaurant") long restaurant);
    String FILTER=" FROM restaurant_favorites f JOIN restaurants r ON r.id=f.restaurant_id WHERE f.user_id=#{user} AND (#{own}=TRUE OR r.deleted_at IS NULL)";
    @Select("SELECT COUNT(*)"+FILTER)
    long count(@Param("user") long user,@Param("own") boolean own);
    /** 本人保留失效占位，但不继续输出失效店铺的名称或状态；他人直接排除且不计数。 */
    @Select("SELECT CAST(f.restaurant_id AS CHAR) restaurant_id,CASE WHEN r.deleted_at IS NULL THEN r.name END name,CASE WHEN r.deleted_at IS NULL THEN r.restaurant_type END restaurant_type,CASE WHEN r.deleted_at IS NULL THEN r.business_status END business_status,(r.deleted_at IS NULL) available,f.created_at"+FILTER+" ORDER BY f.created_at DESC,f.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<FavoriteViews.Item> page(@Param("user") long user,@Param("own") boolean own,@Param("limit") int limit,@Param("offset") long offset);
}
