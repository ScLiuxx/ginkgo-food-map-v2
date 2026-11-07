package com.ginkgo.foodmap.user.mapper;
import com.ginkgo.foodmap.user.vo.FavoriteVisibilityVo;
import org.apache.ibatis.annotations.*;
/** 用户模块独占收藏可见性字段；餐厅模块只能调用公开服务。 */
@Mapper
public interface FavoritePrivacyMapper {
    @Select("SELECT CAST(id AS CHAR) user_id,favorites_public FROM users WHERE id=#{user} AND status<>'DELETED' AND email_verified=TRUE")
    FavoriteVisibilityVo find(long user);
    /** 保存前锁当前有效账号，避免同时编辑简介或版本时整行覆盖。 */
    @Select("SELECT CAST(id AS CHAR) user_id,favorites_public FROM users WHERE id=#{user} AND status='ACTIVE' AND email_verified=TRUE FOR UPDATE")
    FavoriteVisibilityVo lock(long user);
    @Update("UPDATE users SET favorites_public=#{value},row_version=row_version+1 WHERE id=#{user}")
    int update(@Param("user") long user,@Param("value") boolean value);
}
