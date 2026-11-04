package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchDocument;
import com.ginkgo.foodmap.restaurant.entity.RestaurantSearchSource;
import com.ginkgo.foodmap.restaurant.entity.RestaurantTagEntity;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantCatalogMapper;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantSearchProjectionMapper;
import com.ginkgo.foodmap.restaurant.policy.RestaurantSearchProjectionPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 内部搜索投影读取：有界批量从本模块MySQL资料组装文档，不访问ES、OSS或其他模块Mapper。
 * 独立RR只读事务保证商户、标签、统计及封面在同一快照中，不继承调用方可能较弱的隔离级别。
 * 只读取已提交资料，不应在业务写事务中调用；没有公开HTTP入口或自动启动任务。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
public class RestaurantSearchProjectionService {
    private final RestaurantSearchProjectionMapper projections;
    private final RestaurantCatalogMapper catalog;
    private final RestaurantPhotoService albums;

    /**
     * 每批最多100店，只接收商户ID；版本与资料从MySQL同一快照读取，调用方不能伪造版本。
     * 审核通过中的资料及相册在同一事务提交，共用该次资料修改推进的版本。
     * 不存在的商户整批失败，不把物理缺失猜成软删除，也不返回不完整批次冒充成功。
     */
    public List<RestaurantSearchDocument> assemble(List<Long> restaurantIds) {
        if (restaurantIds == null || restaurantIds.size() > 100)
            throw new IllegalArgumentException("搜索投影每批最多100家商户");
        var requested = new LinkedHashSet<>(restaurantIds);
        if (requested.isEmpty()) return List.of();
        requested.forEach(id -> {
            if (id == null || id <= 0)
                throw new IllegalArgumentException("商户ID必须是正Long");
        });
        var ids = List.copyOf(requested);
        var rows = projections.selectSources(ids).stream()
                .collect(Collectors.toMap(RestaurantSearchSource::getId, row -> row));
        if (!rows.keySet().equals(requested))
            throw new IllegalStateException("商户投影来源不完整，不能推断缺失商户的删除状态");
        var visible = ids.stream().filter(id -> !rows.get(id).isDeleted()).toList();
        // 软删除只生成墓碑，不读其残留标签和封面；全删除批次不发空IN查询。
        Map<Long, List<String>> tags = visible.isEmpty() ? Map.of() : catalog.selectVisibleTags(visible).stream()
                .collect(Collectors.groupingBy(RestaurantTagEntity::getRestaurantId,
                        Collectors.mapping(RestaurantTagEntity::getTag, Collectors.toList())));
        var covers = visible.isEmpty() ? Map.<Long, com.ginkgo.foodmap.restaurant.vo.RestaurantPhotoViews.Item>of()
                : albums.covers(visible);
        return ids.stream().map(id -> RestaurantSearchProjectionPolicy.assemble(rows.get(id),
                tags.getOrDefault(id, List.of()), covers.containsKey(id) ? covers.get(id).getId() : null,
                rows.get(id).getProjectionVersion())).toList();
    }
}
