package com.ginkgo.foodmap.community.mapper;

import com.ginkgo.foodmap.community.entity.FollowEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 只访问community关注关系表；账号、署名和隐私通过公开服务取得。 */
@Mapper
public interface FollowMapper {
    @Select(
            "SELECT id,follower_user_id,followed_user_id,active FROM user_follows WHERE follower_user_id=#{actor} AND followed_user_id=#{target} FOR UPDATE")
    FollowEntity lock(@Param("actor") long actor, @Param("target") long target);

    @Insert(
            "INSERT INTO user_follows(follower_user_id,followed_user_id) VALUES(#{followerUserId},#{followedUserId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FollowEntity row);

    @Update(
            "UPDATE user_follows SET active=#{active},updated_at=GREATEST(CURRENT_TIMESTAMP(6),created_at) WHERE id=#{id} AND active<>#{active}")
    int set(@Param("id") long id, @Param("active") boolean active);

    @Select(
            "SELECT EXISTS(SELECT 1 FROM user_follows WHERE follower_user_id=#{actor} AND followed_user_id=#{target} AND active=1)")
    boolean following(@Param("actor") long actor, @Param("target") long target);

    String OWNER =
            "<choose><when test='kind == &quot;FOLLOWING&quot;'>follower_user_id</when><otherwise>followed_user_id</otherwise></choose>";
    String MEMBER =
            "<choose><when test='kind == &quot;FOLLOWING&quot;'>followed_user_id</when><otherwise>follower_user_id</otherwise></choose>";

    @Select(
            "<script>SELECT COUNT(*) FROM user_follows WHERE "
                    + OWNER
                    + "=#{user} AND active=1</script>")
    long count(@Param("user") long user, @Param("kind") String kind);

    @Select(
            "<script>SELECT "
                    + MEMBER
                    + " FROM user_follows WHERE "
                    + OWNER
                    + "=#{user} AND active=1 ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<Long> page(
            @Param("user") long user,
            @Param("kind") String kind,
            @Param("limit") int limit,
            @Param("offset") long offset);

    /** 当前页一次批量补全本人状态，不逐项查询。 */
    @Select(
            "<script>SELECT followed_user_id FROM user_follows WHERE follower_user_id=#{actor} AND active=1 AND followed_user_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Long> followingIds(@Param("actor") long actor, @Param("ids") List<Long> ids);
}
