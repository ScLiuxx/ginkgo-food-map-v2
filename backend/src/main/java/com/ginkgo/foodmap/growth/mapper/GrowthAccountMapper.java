package com.ginkgo.foodmap.growth.mapper;

import com.ginkgo.foodmap.growth.entity.GrowthAccountEntity;
import org.apache.ibatis.annotations.*;

/** 只访问growth账户表；所有写入口在业务事务内先取得同一账户锁。 */
@Mapper
public interface GrowthAccountMapper {
    /** 并发初始化只保证账户存在，不依赖无变化更新的受影响行数判断业务结果。 */
    @Insert("INSERT INTO growth_accounts(user_id) VALUES(#{user}) ON DUPLICATE KEY UPDATE user_id=growth_accounts.user_id")
    void initialize(long user);

    /** 主键锁定当前账户；无账户时不制造记录，撤销未知奖励可直接返回不存在。 */
    @Select("SELECT user_id,experience,updated_at FROM growth_accounts WHERE user_id=#{user} FOR UPDATE")
    GrowthAccountEntity lock(long user);

    /** 只读查询不初始化账户，缺失记录由应用层解释为零经验。 */
    @Select("SELECT user_id,experience,updated_at FROM growth_accounts WHERE user_id=#{user}")
    GrowthAccountEntity find(long user);

    /** 防止Long经验溢出；返回0时必须中止外层事务，不提交孤立奖励。 */
    @Update("UPDATE growth_accounts SET experience=experience+#{amount} WHERE user_id=#{user} AND experience<=9223372036854775807-#{amount}")
    int increase(@Param("user") long user, @Param("amount") int amount);

    /** 原奖励扣回必须余额充足，禁止用归零掩盖账目不一致。 */
    @Update("UPDATE growth_accounts SET experience=experience-#{amount} WHERE user_id=#{user} AND experience>=#{amount}")
    int decrease(@Param("user") long user, @Param("amount") int amount);
    /** 批量读取本模块账户；调用方分批且跳过空集合，不跨模块暴露Entity。 */
    @Select("""
        <script>SELECT user_id,experience,updated_at FROM growth_accounts WHERE user_id IN
        <foreach collection="users" item="user" open="(" separator="," close=")">#{user}</foreach>
        </script>
        """)
    java.util.List<GrowthAccountEntity> findAll(@Param("users") java.util.List<Long> users);
}
