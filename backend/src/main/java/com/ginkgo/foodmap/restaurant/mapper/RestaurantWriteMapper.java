package com.ginkgo.foodmap.restaurant.mapper;

import com.ginkgo.foodmap.restaurant.entity.RestaurantCategoryEntity;
import com.ginkgo.foodmap.restaurant.entity.RestaurantManagementEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** restaurant模块写入SQL入口；不继承通用CRUD，防止绕开归属或软删除约束。 */
@Mapper
public interface RestaurantWriteMapper {
    /** 管理快照允许读取删除状态，但只由完成归属/角色校验的服务输出白名单VO。 */
    RestaurantManagementEntity selectManagementById(@Param("id") long id);

    /** 只锁餐厅主行，不锁JOIN分类；所有标签写入都先锁同一主行，避免并发交叉覆盖。 */
    RestaurantManagementEntity lockById(@Param("id") long id);

    /** 共享锁保持保存期间分类启用状态稳定，避免校验后被停用。 */
    RestaurantCategoryEntity lockCategory(@Param("code") String code);

    /** 按MySQL真实去重规则保留首个标签及输入顺序，不在Java近似模拟ai_ci排序规则。 */
    List<String> distinctTags(@Param("tags") List<String> tags);

    /** 自增ID回填到实体；操作者由服务赋值，时间由数据库生成。 */
    int insert(RestaurantManagementEntity entity);

    /** 只替换可编辑字段，WHERE再次校验归属和删除状态，不修改created_by。 */
    int updateCurrent(@Param("row") RestaurantManagementEntity row, @Param("actor") long actor,
                      @Param("admin") boolean admin);

    /** 软删除不删除标签；操作者、时间和原因在同一SQL中更新。 */
    int softDeleteCurrent(@Param("id") long id,
                          @Param("actor") long actor, @Param("admin") boolean admin);

    /** 只恢复已删除行并清空当前删除元数据；恢复权限由服务端独立角色校验。 */
    int restoreCurrent(@Param("id") long id, @Param("actor") long actor);

    /** 必须位于主行锁和主表更新同一个事务内，失败时整组标签及主表一并回滚。 */
    int deleteTags(@Param("id") long id);

    /** 只保存经规范化、去重和总长校验后的有序标签，调用方保证非空。 */
    int insertTags(@Param("id") long id, @Param("tags") List<String> tags);

    /** 私有读取可保留删除餐厅的标签；公开查询仍使用原Mapper的父状态过滤。 */
    List<String> selectTags(@Param("id") long id);

    /** 父食堂主行已加锁后检查所有窗口，避免类型切换破坏窗口外键，不排除软删除窗口。 */
    @org.apache.ibatis.annotations.Select("SELECT EXISTS(SELECT 1 FROM canteen_windows WHERE canteen_id=#{id})")
    boolean hasWindows(@Param("id") long id);
}
