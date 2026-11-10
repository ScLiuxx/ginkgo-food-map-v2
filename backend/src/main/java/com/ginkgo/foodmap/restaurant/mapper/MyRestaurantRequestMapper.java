package com.ginkgo.foodmap.restaurant.mapper;

import com.ginkgo.foodmap.restaurant.dto.MyRestaurantRequestQuery;
import com.ginkgo.foodmap.restaurant.entity.MyRestaurantRequestEntity;
import com.ginkgo.foodmap.restaurant.entity.RestaurantEntity;
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
        SELECT id,restaurant_id,previous_request_id,request_type,status,created_at,reviewed_at,withdrawn_at,
            proposed_data,approved_data,reject_reason
        FROM restaurant_change_requests WHERE id=#{id} AND submitted_by=#{owner}
        """)
    MyRestaurantRequestEntity detail(@Param("owner") long owner, @Param("id") long id);

    /** 修改/撤回前先按本人范围读取类型和目标，不改数据；最终状态仍以锁定读为准。 */
    @Select("""
        SELECT id,restaurant_id,request_type,status
        FROM restaurant_change_requests WHERE id=#{id} AND submitted_by=#{owner}
        """)
    MyRestaurantRequestEntity hint(@Param("owner") long owner, @Param("id") long id);

    /** 锁本人申请行，与管理员审核持有同一行锁；并发修改/撤回/审核只能有一个成功。 */
    @Select("SELECT * FROM restaurant_change_requests WHERE id=#{id} AND submitted_by=#{owner} FOR UPDATE")
    MyRestaurantRequestEntity lock(@Param("owner") long owner, @Param("id") long id);

    /** 读取纠错目标及其当前分类；已删除目标按不可编辑处理，分类为空表示食堂目标。 */
    @Select("SELECT id,category_code FROM restaurants WHERE id=#{id} AND deleted_at IS NULL")
    RestaurantEntity target(long id);

    /** 只更新本人待审申请的业务快照和摘要，不改变类型、目标、提交账号或幂等UUID。 */
    @Update("""
        UPDATE restaurant_change_requests SET proposed_data=#{proposedData},request_hash=#{requestHash}
        WHERE id=#{id} AND submitted_by=#{owner} AND status='PENDING'
        """)
    int updateContent(@Param("owner") long owner, @Param("id") long id,
            @Param("proposedData") String proposedData, @Param("requestHash") byte[] requestHash);

    /** 撤回只改变本人待审状态并记录时间，不写审核字段；重复撤回由服务返回原结果。 */
    @Update("""
        UPDATE restaurant_change_requests SET status='WITHDRAWN',withdrawn_at=CURRENT_TIMESTAMP(6)
        WHERE id=#{id} AND submitted_by=#{owner} AND status='PENDING'
        """)
    int withdraw(@Param("owner") long owner, @Param("id") long id);

    @Select("""
        SELECT p.media_file_id FROM restaurant_change_photos p
        JOIN restaurant_change_requests r ON r.id=p.request_id
        WHERE r.id=#{id} AND r.submitted_by=#{owner} ORDER BY p.position
        """)
    List<Long> photoIds(@Param("owner") long owner, @Param("id") long id);

    /** 修改附图时先按本人范围清空关联；新关联、旧图释放和内容更新在同一事务提交。 */
    @Delete("""
        DELETE FROM restaurant_change_photos WHERE request_id=#{id}
        AND EXISTS(SELECT 1 FROM restaurant_change_requests r WHERE r.id=#{id} AND r.submitted_by=#{owner})
        """)
    int deletePhotos(@Param("owner") long owner, @Param("id") long id);

    @Insert("INSERT INTO restaurant_change_photos(request_id,position,media_file_id) VALUES(#{request},#{position},#{file})")
    int insertPhoto(@Param("request") long request, @Param("position") int position, @Param("file") long file);

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
