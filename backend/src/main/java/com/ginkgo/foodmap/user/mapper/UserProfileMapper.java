package com.ginkgo.foodmap.user.mapper;

import com.ginkgo.foodmap.user.entity.UserProfileEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 仅访问user自有表，资料保存不使用包含密码/角色的整行实体覆盖更新。 */
@Mapper
public interface UserProfileMapper {
    String CURRENT = "SELECT id,username,email,bio FROM users WHERE id=#{userId} AND status='ACTIVE' AND email_verified=TRUE";

    @Select(CURRENT)
    UserProfileEntity find(long userId);

    /** 写入前取得用户行锁，并再次核验可用状态；与既有改密更新在同一行上互斥。 */
    @Select(CURRENT + " FOR UPDATE")
    UserProfileEntity lock(long userId);

    /** 沿用既有行版本，不新增客户端版本协议；credential_version不变，保存简介不会使会话失效。 */
    @Update("UPDATE users SET bio=#{bio},row_version=row_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{userId} AND status='ACTIVE' AND email_verified=TRUE")
    int updateBio(@Param("userId") long userId, @Param("bio") String bio);
}
