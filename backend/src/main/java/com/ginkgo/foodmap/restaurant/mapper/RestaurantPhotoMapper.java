package com.ginkgo.foodmap.restaurant.mapper;

import com.ginkgo.foodmap.restaurant.entity.RestaurantPhotoEntity;
import com.ginkgo.foodmap.restaurant.entity.RestaurantPhotoFileEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 只访问restaurant模块表；相册公开还需来源申请已通过且目标一致，BOUND本身不代表公开。 */
@Mapper
public interface RestaurantPhotoMapper {
    /** 当前目录页最多100家商户，一次查询首图；不逐店查询相册，也不加入目录排序/计数SQL。 */
    @Select("""
        <script>
        SELECT p.* FROM restaurant_photos p
        JOIN restaurants r ON r.id=p.restaurant_id AND r.deleted_at IS NULL
        JOIN restaurant_change_requests q ON q.id=p.source_request_id AND q.status='APPROVED'
          AND q.restaurant_id=p.restaurant_id
        WHERE p.position=0 AND p.restaurant_id IN
        <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach>
        </script>
        """)
    List<RestaurantPhotoEntity> covers(@Param("ids") List<Long> ids);

    /** 公开空相册也必须验证商户可见，不依赖相册是否已有记录。 */
    @Select("SELECT id FROM restaurants WHERE id=#{id} AND deleted_at IS NULL")
    Long visibleRestaurant(long id);

    /** 当前游标须属于本商户的有效正式关联，防止其他相册ID影响分页结果。 */
    @Select("""
        SELECT p.position FROM restaurant_photos p JOIN restaurant_change_requests q ON q.id=p.source_request_id
        WHERE p.restaurant_id=#{restaurant} AND p.id=#{photo}
        AND q.status='APPROVED' AND q.restaurant_id=p.restaurant_id
        """)
    Integer cursorPosition(@Param("restaurant") long restaurant, @Param("photo") long photo);

    /** 多取一条判断下一页；以商户/位置唯一索引读取，不全量加载或使用深offset。 */
    @Select("""
        SELECT p.* FROM restaurant_photos p JOIN restaurant_change_requests q ON q.id=p.source_request_id
        WHERE p.restaurant_id=#{restaurant} AND p.position>#{after}
        AND q.status='APPROVED' AND q.restaurant_id=p.restaurant_id
        ORDER BY p.position,p.id LIMIT #{limit}
        """)
    List<RestaurantPhotoEntity> page(@Param("restaurant") long restaurant, @Param("after") int after,
                                      @Param("limit") int limit);

    /** 单条SQL同时检查商户、正式关联、来源审核及目标，再解析内部文件引用。 */
    @Select("""
        SELECT p.media_file_id,q.submitted_by FROM restaurant_photos p
        JOIN restaurants r ON r.id=p.restaurant_id AND r.deleted_at IS NULL
        JOIN restaurant_change_requests q ON q.id=p.source_request_id AND q.status='APPROVED'
          AND q.restaurant_id=p.restaurant_id
        WHERE p.restaurant_id=#{restaurant} AND p.id=#{photo}
        """)
    RestaurantPhotoFileEntity file(@Param("restaurant") long restaurant, @Param("photo") long photo);

    /** 最多五张，由原申请的既定顺序决定；审核不接受客户端另传文件列表。 */
    @Select("SELECT media_file_id FROM restaurant_change_photos WHERE request_id=#{request} ORDER BY position")
    List<Long> requestFiles(long request);

    /** 调用方已持有商户主行锁；同店追加被串行化，普通MAX读取不会与其他审核分配相同位置。 */
    @Select("SELECT COALESCE(MAX(position),-1) FROM restaurant_photos WHERE restaurant_id=#{restaurant}")
    long lastPosition(long restaurant);

    /** 与正式资料及申请终态在同一事务写入，唯一键防止同文件或同位置重复发布。 */
    @Insert("""
        INSERT INTO restaurant_photos(restaurant_id,media_file_id,position,source_request_id)
        VALUES(#{restaurantId},#{mediaFileId},#{position},#{sourceRequestId})
        """)
    int insert(RestaurantPhotoEntity row);

    /** 单份申请最多五条，供审核回执/管理员详情读取；软删除商户仍保留追溯信息。 */
    @Select("SELECT * FROM restaurant_photos WHERE source_request_id=#{request} ORDER BY position,id")
    List<RestaurantPhotoEntity> byRequest(long request);
}
