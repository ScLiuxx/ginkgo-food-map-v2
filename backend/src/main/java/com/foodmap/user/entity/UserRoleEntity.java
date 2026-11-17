package com.foodmap.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.util.Objects;

/**
 * {@code user_roles} 关系表的持久化对象。
 *
 * <p>MyBatis-Plus 的通用主键方法不直接支持该表的复合主键，因此本对象不声明 {@code @TableId}，
 * 插入和查询由 {@code UserRoleMapper} 的显式 SQL 完成。数据库复合主键继续负责阻止重复授权。</p>
 */
@Data
@TableName("user_roles")
public class UserRoleEntity {

    private Long userId;

    private Long roleId;

    private Instant assignedAt;

    /**
     * 创建待持久化的用户角色关系。
     *
     * @param userId 已持久化用户 ID
     * @param roleId 已持久化角色 ID
     * @return 待插入的关系实体
     */
    public static UserRoleEntity assign(Long userId, Long roleId) {
        UserRoleEntity relation = new UserRoleEntity();
        relation.userId = Objects.requireNonNull(userId, "userId must not be null");
        relation.roleId = Objects.requireNonNull(roleId, "roleId must not be null");
        return relation;
    }
}
