package com.foodmap.user.mapper;

import com.foodmap.user.entity.UserRoleEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code user_roles} 复合主键关系表的显式 Mapper。
 *
 * <p>该表不强行套用 MyBatis-Plus 单主键 CRUD。简单且固定的 SQL 直接写在 Mapper 上，
 * 后续出现多表授权查询时再迁移到 XML，保持 SQL 可审查。</p>
 */
@Mapper
public interface UserRoleMapper {

    /** 角色编码由 user 模块内部关联查询，其他模块不得直接访问角色数据表。 */
    @Select("""
            SELECT r.code FROM roles r JOIN user_roles ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} ORDER BY r.code
            """)
    List<String> selectRoleCodes(Long userId);

    /**
     * 插入用户角色关系，授权时间由数据库统一生成。
     *
     * @param relation 待插入的用户 ID 与角色 ID
     * @return 受影响行数，成功时为 1
     */
    @Insert("INSERT INTO user_roles (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insert(UserRoleEntity relation);

    /**
     * 查询用户拥有的全部角色关系。
     *
     * @param userId 用户 ID
     * @return 用户角色关系列表
     */
    @Select("""
            SELECT user_id, role_id, assigned_at
            FROM user_roles
            WHERE user_id = #{userId}
            ORDER BY role_id
            """)
    List<UserRoleEntity> selectByUserId(Long userId);
}
