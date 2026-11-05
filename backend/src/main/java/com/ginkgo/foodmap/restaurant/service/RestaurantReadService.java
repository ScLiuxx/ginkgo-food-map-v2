package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.restaurant.support.RestaurantIdentifiers;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.entity.RestaurantEntity;
import com.ginkgo.foodmap.restaurant.entity.RestaurantTagEntity;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantCatalogMapper;
import com.ginkgo.foodmap.restaurant.vo.RestaurantCategoryVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantPageVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantMapPointsVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantMapPointVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchResult;
import com.ginkgo.foodmap.restaurant.exception.RestaurantSearchStaleException;
import org.springframework.beans.BeanUtils;

/**
 * 公开餐厅读取应用服务，只读取本模块的餐厅、字典、标签及评价统计读模型。
 * MySQL REPEATABLE_READ只读事务保证COUNT、记录与标签来自同一快照，避免并发修改导致口径漂移。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RestaurantReadService {
    private final RestaurantCatalogMapper mapper;
    private final RestaurantPhotoService albums;

    /** 面向其他模块的只读可见性校验，不初始化统计、不取得写锁。 */
    public void requireVisible(long id) {
        if(mapper.selectVisibleById(id)==null)throw new BusinessException(RestaurantErrorCode.NOT_FOUND);
    }

    /** 返回受控分类全集及启用状态，历史分类不会因停用而消失。 */
    public List<RestaurantCategoryVo> categories() {
        return mapper.selectCategories().stream().map(entity -> {
            RestaurantCategoryVo vo = new RestaurantCategoryVo();
            vo.setCode(entity.getCode());
            vo.setName(entity.getName());
            vo.setSortOrder(entity.getSortOrder());
            vo.setEnabled(entity.isEnabled());
            return vo;
        }).toList();
    }

    /** 数据库完成全局查询后只批量补充当前页标签，不进行页内筛选或重新排序。 */
    public RestaurantPageVo list(RestaurantListQuery query) {
        long total = mapper.countVisible(query);
        List<RestaurantEntity> rows = query.getOffset() >= total ? List.of() : mapper.selectVisiblePage(query);
        Map<Long, List<String>> tags = tagsFor(rows);
        var covers = albums.covers(rows.stream().map(RestaurantEntity::getId).toList());
        RestaurantPageVo page = new RestaurantPageVo();
        page.setRecords(rows.stream().map(row -> {
            var result = toVo(row, tags.getOrDefault(row.getId(), List.of()));
            result.setCoverPhoto(covers.get(row.getId()));
            return result;
        }).toList());
        page.setTotal(total);
        page.setPage(query.getPage());
        page.setPageSize(query.getPageSize());
        page.setTotalPages(total / query.getPageSize() + (total % query.getPageSize() == 0 ? 0 : 1));
        page.setAccessiblePages(page.getTotalPages());
        return page;
    }

    /** 查询前保守检查当前版本积压；仅访问已有Outbox，不引入后台健康检查或熔断状态。 */
    public boolean hasUnsyncedSearchChanges() { return mapper.hasUnsyncedSearchChanges(); }

    /** ES只确定候选顺序；当前页可见性、全部硬条件、版本、标签及封面在一个RR快照内读取。 */
    public RestaurantPageVo searchList(RestaurantListQuery query, RestaurantSearchResult result) {
        var rows = currentSearchRows(query, result);
        var tags = tagsFor(rows);
        var covers = albums.covers(rows.stream().map(RestaurantEntity::getId).toList());
        var page = new RestaurantPageVo();
        page.setRecords(rows.stream().map(row -> {
            var vo = toVo(row, tags.getOrDefault(row.getId(), List.of()));
            vo.setCoverPhoto(covers.get(row.getId()));return vo;
        }).toList());
        page.setTotal(result.getTotal());page.setPage(query.getPage());page.setPageSize(query.getPageSize());
        page.setTotalPages(result.getTotal() / query.getPageSize() + (result.getTotal() % query.getPageSize() == 0 ? 0 : 1));
        page.setAccessiblePages(page.getTotalPages());return page;
    }

    /** 地图也回查同一版本及硬条件，保留100点限制；不复用分页列表的前8条冒充地图集合。 */
    public RestaurantMapPointsVo searchMap(RestaurantListQuery query, RestaurantSearchResult result) {
        var rows = currentSearchRows(query, result);
        var points = rows.stream().map(row -> {
            var point = new RestaurantMapPointVo();point.setId(row.getId().toString());point.setName(row.getName());
            point.setLatitude(row.getLatitude());point.setLongitude(row.getLongitude());point.setScore(row.getScore());
            point.setPricePerPerson(row.getPricePerPerson());point.setBusinessStatus(row.getBusinessStatus());return point;
        }).toList();
        var response = new RestaurantMapPointsVo();response.setPoints(points);response.setMatchedCount(result.getTotal());
        response.setReturnedCount(points.size());response.setLimit(100);response.setTruncated(result.getTotal() > points.size());
        return response;
    }

    private List<RestaurantEntity> currentSearchRows(RestaurantListQuery query, RestaurantSearchResult result) {
        // 再次检查覆盖ES请求期间提交的新建/恢复；确认成功到refresh可见仍存在最终一致性窗口。
        if (mapper.hasUnsyncedSearchChanges()) throw new RestaurantSearchStaleException();
        if (result.getHits().isEmpty()) return List.of();
        var current = new RestaurantListQuery();BeanUtils.copyProperties(query, current);current.setKeyword("");
        var ids = result.getHits().stream().map(RestaurantSearchResult.Hit::getId).toList();
        var rows = mapper.selectSearchPage(current, ids).stream()
                .collect(Collectors.toMap(RestaurantEntity::getId, row -> row));
        if (rows.size() != ids.size()) throw new RestaurantSearchStaleException();
        return result.getHits().stream().map(hit -> {
            var row = rows.get(hit.getId());
            if (row == null || row.getSearchProjectionVersion() == null
                    || row.getSearchProjectionVersion() != hit.getProjectionVersion()) throw new RestaurantSearchStaleException();
            return row;
        }).toList();
    }

    /** 地图必须显式指定完整视口，COUNT与点位保持同一事务快照，不加载无用标签。 */
    public RestaurantMapPointsVo mapPoints(RestaurantListQuery query) {
        if (query.getWest() == null || query.getEast() == null || query.getSouth() == null
                || query.getNorth() == null || !query.isBoundsValid()) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        }
        long count = mapper.countVisible(query);
        var points = count == 0 ? List.<RestaurantMapPointVo>of() : mapper.selectMapPoints(query).stream().map(row -> {
            var point = new RestaurantMapPointVo();
            point.setId(row.getId().toString()); point.setName(row.getName());
            point.setLatitude(row.getLatitude()); point.setLongitude(row.getLongitude());
            point.setScore(row.getScore()); point.setPricePerPerson(row.getPricePerPerson());
            point.setBusinessStatus(row.getBusinessStatus());
            return point;
        }).toList();
        var response = new RestaurantMapPointsVo();
        response.setPoints(points); response.setMatchedCount(count); response.setReturnedCount(points.size());
        response.setLimit(100); response.setTruncated(count > points.size());
        return response;
    }

    /** 正整数ID限定在Java Long范围，已删除和不存在使用统一错误，不暴露任何管理元数据。 */
    public RestaurantVo detail(String id) {
        long value = RestaurantIdentifiers.id(id);
        RestaurantEntity row = mapper.selectVisibleById(value);
        if (row == null) throw new BusinessException(RestaurantErrorCode.NOT_FOUND);
        var result = toVo(row, tagsFor(List.of(row)).getOrDefault(row.getId(), List.of()));
        result.setCoverPhoto(albums.covers(List.of(row.getId())).get(row.getId()));
        return result;
    }

    private Map<Long, List<String>> tagsFor(List<RestaurantEntity> rows) {
        if (rows.isEmpty()) return Map.of();
        return mapper.selectVisibleTags(rows.stream().map(RestaurantEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(RestaurantTagEntity::getRestaurantId,
                        Collectors.mapping(RestaurantTagEntity::getTag, Collectors.toList())));
    }

    /** 显式映射公开白名单；直接使用同一数据库快照中的统计，不按点评当前页另算均分。 */
    static RestaurantVo toVo(RestaurantEntity row, List<String> tags) {
        RestaurantVo vo = new RestaurantVo();
        vo.setId(row.getId().toString());
        vo.setName(row.getName());
        vo.setCategoryCode(row.getCategoryCode());
        vo.setRestaurantType(row.getRestaurantType());
        vo.setSupportsDineIn(row.getSupportsDineIn());
        vo.setSupportsDelivery(row.getSupportsDelivery());
        vo.setCategoryName(row.getCategoryName());
        vo.setPricePerPerson(row.getPricePerPerson());
        vo.setPriceKind(row.getPriceKind());
        vo.setAddress(row.getAddress());
        vo.setLatitude(row.getLatitude());
        vo.setLongitude(row.getLongitude());
        vo.setNeedsLocation(row.isNeedsLocation());
        vo.setBusinessStatus(row.getBusinessStatus());
        vo.setCreatedAt(row.getCreatedAt());
        vo.setUpdatedAt(row.getUpdatedAt());
        vo.setDistanceKm(row.getDistanceKm());
        vo.setTags(tags);
        vo.setScore(row.getScore());
        vo.setRecommendRate(row.getRecommendRate());
        vo.setReviewCount(row.getReviewCount());
        vo.setParticipantCount(row.getParticipantCount());
        var dimensions = new RestaurantVo.Dimensions();
        dimensions.setTaste(row.getTaste());
        dimensions.setValue(row.getValue());
        dimensions.setService(row.getService());
        dimensions.setAmbience(row.getAmbience());
        vo.setDimensions(dimensions);
        return vo;
    }
}
