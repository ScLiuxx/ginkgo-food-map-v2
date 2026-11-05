package com.ginkgo.foodmap.restaurant.mapper;

import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.entity.RestaurantCategoryEntity;
import com.ginkgo.foodmap.restaurant.entity.RestaurantEntity;
import com.ginkgo.foodmap.restaurant.entity.RestaurantTagEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 仅查询restaurant模块自己的餐厅、分类、标签和统计表，全部SQL位于同名XML。
 * 不继承通用 CRUD Mapper，防止调用默认 selectById 绕过 deleted_at 显式过滤。
 */
@Mapper
public interface RestaurantCatalogMapper {
    /** 包含停用分类以支持旧记录筛选，按字典顺序和编码稳定返回。 */
    List<RestaurantCategoryEntity> selectCategories();

    /** 公开详情只读未删除餐厅，缺失和已删除均返回 null。 */
    RestaurantEntity selectVisibleById(@Param("id") long id);

    /** 与分页共用 WHERE 片段，不通过标签连接重复计数。 */
    long countVisible(@Param("query") RestaurantListQuery query);

    /** 全局筛选与白名单排序在 LIMIT 之前执行，offset 使用 long。 */
    List<RestaurantEntity> selectVisiblePage(@Param("query") RestaurantListQuery query);

    /** 有界回查当前页；复用全部非关键词筛选，不能拿LIKE再次过滤ES的分词命中。 */
    List<RestaurantEntity> selectSearchPage(@Param("query") RestaurantListQuery query, @Param("ids") List<Long> ids);

    /** 当前版本仍有未完成任务时保守走SQL，防止新增/恢复商户因尚未入索引而漏召回。 */
    boolean hasUnsyncedSearchChanges();

    /** 独立于目录分页读取视口点位；最多100条，避免点位过密且不接受客户端扩大限额。 */
    List<RestaurantEntity> selectMapPoints(@Param("query") RestaurantListQuery query);

    /** 仅批量读取本页可见餐厅的标签，并再次检查父餐厅未删除。 */
    List<RestaurantTagEntity> selectVisibleTags(@Param("ids") List<Long> ids);
}
