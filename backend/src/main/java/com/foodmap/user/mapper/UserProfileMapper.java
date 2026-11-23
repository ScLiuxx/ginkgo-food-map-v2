package com.foodmap.user.mapper;

import com.foodmap.user.entity.UserProfileEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 仅访问user自有表，资料保存不使用包含密码/角色的整行实体覆盖更新。 */
@Mapper
public interface UserProfileMapper {
    String CURRENT =
            "SELECT id,username,email,bio,avatar_file_id FROM users WHERE id=#{userId} AND"
                + " status='ACTIVE' AND email_verified=TRUE";

    /** 头像作为公开内容署名；停用账号保留历史署名，删除或未验证账号不公开。 */
    @Select(
            "SELECT id,avatar_file_id FROM users WHERE id=#{userId} AND status<>'DELETED' AND"
                + " email_verified=TRUE")
    UserProfileEntity findAvatar(long userId);

    /** 仅替换头像，不修改密码、凭证版本或简介，已有会话继续有效。 */
    @Update(
            "UPDATE users SET"
                + " avatar_file_id=#{fileId},row_version=row_version+1,updated_at=CURRENT_TIMESTAMP(6)"
                + " WHERE id=#{userId} AND status='ACTIVE' AND email_verified=TRUE")
    int updateAvatar(@Param("userId") long userId, @Param("fileId") long fileId);

    @Select(CURRENT)
    UserProfileEntity find(long userId);

    /** 写入前取得用户行锁，并再次核验可用状态；与既有改密更新在同一行上互斥。 */
    @Select(CURRENT + " FOR UPDATE")
    UserProfileEntity lock(long userId);

    /** 沿用既有行版本，不新增客户端版本协议；credential_version不变，保存简介不会使会话失效。 */
    @Update(
            "UPDATE users SET bio=#{bio},row_version=row_version+1,updated_at=CURRENT_TIMESTAMP(6)"
                + " WHERE id=#{userId} AND status='ACTIVE' AND email_verified=TRUE")
    int updateBio(@Param("userId") long userId, @Param("bio") String bio);

    /** 会话和已有内容按用户ID关联，改名只推进资料行版本。 */
    @Update(
            "UPDATE users SET username=#{username},row_version=row_version+1,updated_at=CURRENT_TIMESTAMP(6)"
                + " WHERE id=#{userId} AND status='ACTIVE' AND email_verified=TRUE")
    int updateUsername(@Param("userId") long userId, @Param("username") String username);
}
