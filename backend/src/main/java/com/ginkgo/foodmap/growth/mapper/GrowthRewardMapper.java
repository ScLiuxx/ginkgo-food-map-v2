package com.ginkgo.foodmap.growth.mapper;

import com.ginkgo.foodmap.growth.entity.GrowthRewardEntity;
import org.apache.ibatis.annotations.*;

/** 只操作growth奖励表；长期业务键与可追溯来源独立保存，不访问点评表。 */
@Mapper
public interface GrowthRewardMapper {
    String COLUMNS = "id,user_id,reward_type,business_key,source_type,source_id,amount,status,granted_at,revoked_at,disposition_id";

    /** 审核奖励按可信服务提供的类型和资格查询，已撤销记录继续占用资格。 */
    @Select("SELECT " + COLUMNS + " FROM growth_rewards WHERE user_id=#{user} AND reward_type=#{type} AND business_key=#{key}")
    GrowthRewardEntity approval(@Param("user") long user, @Param("type") String type, @Param("key") String key);

    /** 类型由成长服务白名单确定，申请来源固定，不能混入点评来源。 */
    @Insert("INSERT INTO growth_rewards(user_id,reward_type,business_key,source_type,source_id,amount) VALUES(#{userId},#{rewardType},#{businessKey},'RESTAURANT_REQUEST',#{sourceId},#{amount})")
    int insertApproval(GrowthRewardEntity row);

    /** 同用户同店的不同点评仍命中同一个奖励资格，撤销后也保留该资格记录。 */
    @Select("SELECT " + COLUMNS + " FROM growth_rewards WHERE user_id=#{user} AND reward_type='FIRST_STORE_REVIEW' AND business_key=#{key}")
    GrowthRewardEntity firstStoreReview(@Param("user") long user, @Param("key") String key);

    /** 原奖励的受益账户由可信业务调用者传递，再按同账户范围核对奖励ID。 */
    @Select("SELECT " + COLUMNS + " FROM growth_rewards WHERE id=#{id} AND user_id=#{user}")
    GrowthRewardEntity find(@Param("user") long user, @Param("id") long id);

    /** SQL固定业务种类和来源，调用者只提供服务端事实及配置产生的正奖励值。 */
    @Insert("INSERT INTO growth_rewards(user_id,reward_type,business_key,source_type,source_id,amount) VALUES(#{userId},'FIRST_STORE_REVIEW',#{businessKey},'REVIEW',#{sourceId},#{amount})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertFirstStoreReview(GrowthRewardEntity row);

    /** 只有有效状态才发生转换；时间回拨时仍满足撤销不早于发放的存储约束。 */
    @Update("UPDATE growth_rewards SET status='REVOKED',revoked_at=GREATEST(CURRENT_TIMESTAMP(6),granted_at),disposition_id=#{disposition} WHERE id=#{id} AND user_id=#{user} AND status='GRANTED'")
    int revoke(@Param("user") long user, @Param("id") long id, @Param("disposition") long disposition);
    /** 在同作者账户锁内统计，撤销不释放当日额度。 */
    @Select("SELECT COALESCE(SUM(amount),0) FROM growth_rewards WHERE user_id=#{user} AND reward_type='REVIEW_LIKED' AND business_date=#{date}")
    long dailyAmount(@Param("user") long user,@Param("date") java.time.LocalDate date);
    @Select("SELECT COUNT(*) FROM growth_rewards WHERE user_id=#{user} AND reward_type='REVIEW_LIKED' AND business_date=#{date} AND actor_user_id=#{actor}")
    long dailyActor(@Param("user") long user,@Param("date") java.time.LocalDate date,@Param("actor") long actor);
    @Insert("INSERT INTO growth_rewards(user_id,reward_type,business_key,source_type,source_id,amount,actor_user_id,business_date) VALUES(#{userId},'REVIEW_LIKED',#{businessKey},'REVIEW',#{sourceId},#{amount},#{actorUserId},#{businessDate})")
    int insertLike(GrowthRewardEntity row);
}
