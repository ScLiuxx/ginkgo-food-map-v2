package com.ginkgo.foodmap.review.mapper;

import com.ginkgo.foodmap.review.entity.*;
import com.ginkgo.foodmap.review.vo.ContentLikeViews;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 只访问review自有关系、点评和回复表；动态值一律绑定，类型只允许固定分支。 */
@Mapper
public interface ContentLikeMapper {
    String IDS =
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>";

    /** 批量读取点评或回复的最小来源事实；父店可见性由餐厅公开服务核验。 */
    @Select(
            "<script><choose><when test='type == &quot;REVIEW&quot;'>SELECT id,id review_id,restaurant_id,author_user_id,body,created_at FROM reviews WHERE id IN "
                    + IDS
                    + "</when><otherwise>SELECT p.id,p.review_id,r.restaurant_id,p.author_user_id,p.body,p.created_at FROM review_replies p JOIN reviews r ON r.id=p.review_id WHERE p.id IN "
                    + IDS
                    + "</otherwise></choose></script>")
    List<LikeTargetEntity> targets(@Param("type") String type, @Param("ids") List<Long> ids);

    /** 锁定当前用户的唯一关系，取消与重新点赞共用同一行。 */
    @Select(
            "SELECT * FROM content_likes WHERE target_type=#{type} AND target_id=#{id} AND user_id=#{user} FOR UPDATE")
    ContentLikeEntity lock(
            @Param("type") String type, @Param("id") long id, @Param("user") long user);

    /** 按通知来源读取首次关系，保留已取消关系以定位原内容。 */
    @Select("SELECT * FROM content_likes WHERE id=#{id}")
    ContentLikeEntity find(long id);

    /** 插入首次关系并回填编号；唯一键冲突由外层整笔事务重试处理。 */
    @Insert(
            "INSERT INTO content_likes(target_type,target_id,user_id) VALUES(#{targetType},#{targetId},#{userId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ContentLikeEntity row);

    /** 仅在状态改变时更新时间，重复请求保持原始事实。 */
    @Update(
            "UPDATE content_likes SET active=#{active},updated_at=GREATEST(CURRENT_TIMESTAMP(6),created_at) WHERE id=#{id} AND active<>#{active}")
    int setActive(@Param("id") long id, @Param("active") boolean active);

    /** 只聚合当前有效关系，游客不匹配任何个人点赞状态。 */
    @Select(
            "<script>SELECT CAST(target_id AS CHAR) target_id,COUNT(*) count,MAX(user_id=COALESCE(#{user},0)) liked FROM content_likes WHERE target_type=#{type} AND active=1 AND target_id IN "
                    + IDS
                    + " GROUP BY target_id</script>")
    List<ContentLikeViews.State> counts(
            @Param("type") String type, @Param("ids") List<Long> ids, @Param("user") Long user);

    /** 有效内容才计入当前获赞；删除点评也自然排除其已删除回复。 */
    @Select(
            "SELECT (SELECT COUNT(*) FROM content_likes l JOIN reviews r ON l.target_type='REVIEW' AND l.target_id=r.id WHERE r.author_user_id=#{user} AND l.active=1)+(SELECT COUNT(*) FROM content_likes l JOIN review_replies p ON l.target_type='REPLY' AND l.target_id=p.id JOIN reviews r ON r.id=p.review_id WHERE p.author_user_id=#{user} AND l.active=1)")
    long received(long user);
}
