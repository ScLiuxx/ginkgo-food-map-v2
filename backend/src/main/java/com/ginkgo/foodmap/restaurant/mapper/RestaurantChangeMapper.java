package com.ginkgo.foodmap.restaurant.mapper;

import com.ginkgo.foodmap.restaurant.entity.RestaurantChangeEntity;
import com.ginkgo.foodmap.restaurant.entity.RestaurantEntity;
import org.apache.ibatis.annotations.*;

/** 商户申请与正式商户同属restaurant模块；锁定目标防止与删除交错。 */
@Mapper
public interface RestaurantChangeMapper {
    /** 按展示顺序保存商户模块自身关联；禁止直接操作file表。 */
    @Insert("INSERT INTO restaurant_change_photos(request_id,position,media_file_id) VALUES(#{request},#{position},#{file})")
    int insertPhoto(@Param("request") long request, @Param("position") int position, @Param("file") long file);

    /** 幂等重试必须先核对已有照片顺序，不能再次绑定BOUND文件。 */
    @Select("SELECT media_file_id FROM restaurant_change_photos WHERE request_id=#{request} ORDER BY position")
    java.util.List<Long> photoIds(long request);

    @Select("SELECT id,category_code FROM restaurants WHERE id=#{id} AND deleted_at IS NULL FOR UPDATE")
    RestaurantEntity lockTarget(@Param("id") long id);

    /** 锁定读确保并发重试看到已提交回执，不使用普通快照读判断是否重复。 */
    @Select("SELECT * FROM restaurant_change_requests WHERE submitted_by=#{actor} AND client_request_id=#{request} FOR UPDATE")
    RestaurantChangeEntity findRequest(@Param("actor") long actor, @Param("request") String request);

    @Insert("""
        INSERT INTO restaurant_change_requests(restaurant_id,request_type,submitted_by,client_request_id,request_hash,proposed_data)
        VALUES(#{restaurantId},#{requestType},#{submittedBy},#{clientRequestId},#{requestHash},#{proposedData})
        ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RestaurantChangeEntity row);
}
