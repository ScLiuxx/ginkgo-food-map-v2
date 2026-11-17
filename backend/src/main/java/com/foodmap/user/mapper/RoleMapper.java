package com.foodmap.user.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.foodmap.user.entity.RoleEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/**
 * {@code roles} 表的 MyBatis-Plus Mapper。
 */
@Mapper
public interface RoleMapper extends BaseMapper<RoleEntity> {

    /**
     * 按稳定角色编码查询角色，授权逻辑不得使用展示名称进行匹配。
     *
     * @param code 角色编码，例如 {@code ROLE_USER}
     * @return 匹配角色，不存在时为空
     */
    default Optional<RoleEntity> selectByCode(String code) {
        LambdaQueryWrapper<RoleEntity> query = new LambdaQueryWrapper<RoleEntity>()
                .eq(RoleEntity::getCode, code);
        return Optional.ofNullable(selectOne(query));
    }
}
