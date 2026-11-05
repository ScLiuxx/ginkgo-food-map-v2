package com.ginkgo.foodmap.restaurant.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.ginkgo.foodmap.restaurant.entity.RestaurantSearchTask;
import java.util.List;

/** 只操作restaurant模块的商户及Outbox；版本从数据库读取，调用方不能伪造。 */
@Mapper
public interface RestaurantSearchOutboxMapper {
    /** 调用方已持有商户主行写锁；与业务事务共用连接读取刚生成的版本，缺失或重复均不能忽略。 */
    @Insert("""
        INSERT INTO restaurant_search_outbox(restaurant_id, projection_version)
        SELECT id, search_projection_version FROM restaurants WHERE id=#{restaurantId}
        """)
    int insertCurrentVersion(@Param("restaurantId") long restaurantId);

    /** 本方案仅允许一个消费实例；不锁行、不设PROCESSING，进程重启可直接重新扫描。 */
    @Select("""
        SELECT id,restaurant_id,projection_version,attempts FROM restaurant_search_outbox
        WHERE status='PENDING' AND attempts < #{maxAttempts} AND next_attempt_at <= CURRENT_TIMESTAMP(6)
        ORDER BY next_attempt_at,id LIMIT #{limit}
        """)
    List<RestaurantSearchTask> selectDue(@Param("limit") int limit, @Param("maxAttempts") int maxAttempts);

    /** 仅确认本次任务，不能按商户ID顺带确认后来产生的任务。失败次数条件防止迟到更新覆盖状态。 */
    @Update("""
        UPDATE restaurant_search_outbox SET status='SUCCEEDED',completed_at=CURRENT_TIMESTAMP(6),last_error=NULL
        WHERE id=#{id} AND status='PENDING' AND attempts=#{attempts}
        """)
    int succeed(@Param("id") long id, @Param("attempts") int attempts);

    /** status先按旧attempts判断，再递增；达到上限保留失败记录，不删除任务。 */
    @Update("""
        UPDATE restaurant_search_outbox
        SET status=CASE WHEN attempts+1 >= #{maxAttempts} THEN 'FAILED' ELSE 'PENDING' END,
            attempts=attempts+1,
            next_attempt_at=TIMESTAMPADD(MICROSECOND,#{retryDelayMs}*1000,CURRENT_TIMESTAMP(6)),last_error=#{error}
        WHERE id=#{id} AND status='PENDING' AND attempts=#{attempts}
        """)
    int fail(@Param("id") long id, @Param("attempts") int attempts, @Param("maxAttempts") int maxAttempts,
             @Param("retryDelayMs") long retryDelayMs, @Param("error") String error);
}
