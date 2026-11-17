package com.foodmap.restaurant.mapper;

import com.foodmap.restaurant.entity.RestaurantRankingSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读取restaurant模块自己的已生成快照；不关联点评、餐厅详情或触发计算。 */
@Mapper
public interface RestaurantRankingSnapshotMapper {
    /** 联合主键定位一行；不存在返回null，不能将尚未生成伪装为成功空榜。 */
    RestaurantRankingSnapshotEntity selectSnapshot(
            @Param("campusCode") String campusCode, @Param("periodType") String periodType);

    /** 仅由内部发布服务在同一事务与全局缓存锁内写入，不对外提供写榜接口。 */
    void upsertSnapshot(@Param("row") RestaurantRankingSnapshotEntity row);
}
