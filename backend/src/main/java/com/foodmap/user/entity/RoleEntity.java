package com.foodmap.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.util.Objects;

/**
 * {@code roles} 表的持久化对象。
 *
 * <p>授权判断使用稳定的角色编码，展示名称仅用于界面显示。内置角色通常由 Flyway 初始化。</p>
 */
@Data
@TableName("roles")
public class RoleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    private Instant createdAt;

    /**
     * 创建测试或受控管理流程使用的角色定义。
     *
     * @param code 稳定且唯一的角色编码
     * @param name 角色展示名称
     * @return 待持久化角色实体
     */
    public static RoleEntity create(String code, String name) {
        RoleEntity role = new RoleEntity();
        role.code = requireText(code, "code").trim();
        role.name = requireText(name, "name").trim();
        return role;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
