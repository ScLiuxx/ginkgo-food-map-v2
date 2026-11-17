package com.foodmap.user.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.foodmap.user.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

/**
 * {@code users} 表的 MyBatis-Plus Mapper，仅允许 user 模块内部使用。
 *
 * <p>调用方必须先规范化用户名和邮箱。存在性查询只用于快速返回友好错误，
 * 并发注册的最终正确性仍由 MySQL 唯一约束保证。</p>
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    /**
     * 在同一 SQL 中替换密码并递增凭证版本和行版本，避免改密与旧令牌失效分两步执行。
     *
     * <p>只更新密码相关列，且显式比较读取时的两个版本、邮箱和可登录状态。此自定义 SQL 不依赖
     * MyBatis-Plus 的实体乐观锁拦截器，返回0表示账号已变化，调用方必须拒绝并要求重新获取验证码。</p>
     *
     * @return 成功为1，账号并发变化或不可用为0
     */
    @Update("""
            UPDATE users
            SET password_hash = #{passwordHash}, credential_version = credential_version + 1,
                row_version = row_version + 1, updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{userId} AND email = #{email} AND row_version = #{rowVersion}
                AND credential_version = #{credentialVersion} AND status = 'ACTIVE' AND email_verified = TRUE
            """)
    int resetPasswordIfCurrent(@Param("userId") long userId, @Param("email") String email,
                               @Param("rowVersion") long rowVersion,
                               @Param("credentialVersion") long credentialVersion,
                               @Param("passwordHash") String passwordHash);

    /**
     * 按规范化用户名查询用户。
     *
     * @param username 已去除首尾空白的用户名
     * @return 匹配用户，不存在时为空
     */
    default Optional<UserEntity> selectByUsername(String username) {
        LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username);
        return Optional.ofNullable(selectOne(query));
    }

    /**
     * 按规范化校园邮箱查询用户。
     *
     * @param email 已转换为小写的完整邮箱
     * @return 匹配用户，不存在时为空
     */
    default Optional<UserEntity> selectByEmail(String email) {
        LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getEmail, email);
        return Optional.ofNullable(selectOne(query));
    }

    /**
     * 注册前快速检查用户名是否存在。
     *
     * @param username 已规范化用户名
     * @return 至少存在一条记录时返回 {@code true}
     */
    default boolean existsByUsername(String username) {
        LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username);
        return selectCount(query) > 0;
    }

    /**
     * 注册前快速检查邮箱是否存在。
     *
     * @param email 已规范化邮箱
     * @return 至少存在一条记录时返回 {@code true}
     */
    default boolean existsByEmail(String email) {
        LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getEmail, email);
        return selectCount(query) > 0;
    }
}
