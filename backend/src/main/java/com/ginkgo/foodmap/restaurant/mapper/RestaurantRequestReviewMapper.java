package com.ginkgo.foodmap.restaurant.mapper;

import com.ginkgo.foodmap.restaurant.entity.RestaurantChangeEntity;
import org.apache.ibatis.annotations.*;

/** 申请终态只能从PENDING进入；所有审核元数据在单条更新中满足数据库CHECK约束。 */
@Mapper
public interface RestaurantRequestReviewMapper {
    /** 无锁读取不可变类型与原目标，决定UPDATE的商户→申请锁顺序；状态必须随后锁定重读。 */
    @Select("SELECT * FROM restaurant_change_requests WHERE id=#{id}")
    RestaurantChangeEntity find(long id);

    /** 读取最新已提交状态并持有申请排他锁，直到发布/驳回事务结束。 */
    @Select("SELECT * FROM restaurant_change_requests WHERE id=#{id} FOR UPDATE")
    RestaurantChangeEntity lock(long id);

    /** 仅在首次通过时写入目标及完整审核元数据，不触碰用户原始快照和摘要。 */
    @Update("""
        UPDATE restaurant_change_requests SET status='APPROVED',restaurant_id=#{restaurantId},
          reviewed_by=#{reviewedBy},reviewed_at=CURRENT_TIMESTAMP(6),approved_data=#{approvedData},before_data=#{beforeData}
        WHERE id=#{id} AND status='PENDING'
        """)
    int approve(RestaurantChangeEntity row);

    /** 驳回不改变目标、业务快照和图片关联，重复请求由服务核对原因并返回原回执。 */
    @Update("""
        UPDATE restaurant_change_requests SET status='REJECTED',reviewed_by=#{reviewedBy},
          reviewed_at=CURRENT_TIMESTAMP(6),reject_reason=#{rejectReason}
        WHERE id=#{id} AND status='PENDING'
        """)
    int reject(RestaurantChangeEntity row);
}
