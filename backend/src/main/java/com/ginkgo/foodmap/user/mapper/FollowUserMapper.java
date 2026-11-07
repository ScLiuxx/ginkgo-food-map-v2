package com.ginkgo.foodmap.user.mapper;

import com.ginkgo.foodmap.user.entity.FollowUserEntity;
import com.ginkgo.foodmap.user.vo.FollowPrivacyVo;

import org.apache.ibatis.annotations.*;

/** 用户模块独占账号状态及名单隐私，不让community跨模块查用户表。 */
@Mapper
public interface FollowUserMapper {
    @Select(
            "SELECT CAST(id AS CHAR) user_id,following_public,followers_public FROM users WHERE id=#{id} AND status<>'DELETED' AND email_verified=TRUE")
    FollowPrivacyVo privacy(long id);

    @Select("SELECT id,status,email_verified FROM users WHERE id=#{id} FOR UPDATE")
    FollowUserEntity lock(long id);

    @Update(
            "UPDATE users SET following_public=#{following},followers_public=#{followers},row_version=row_version+1 WHERE id=#{id}")
    int update(
            @Param("id") long id,
            @Param("following") boolean following,
            @Param("followers") boolean followers);
}
