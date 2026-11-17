package com.foodmap.restaurant.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.restaurant.config.RestaurantRankingProperties;
import com.foodmap.restaurant.dto.RestaurantListQuery;
import com.foodmap.restaurant.dto.RestaurantPickerRequest;
import com.foodmap.restaurant.exception.RestaurantErrorCode;
import com.foodmap.restaurant.mapper.RestaurantCatalogMapper;
import com.foodmap.restaurant.policy.RestaurantPickerPolicy;
import com.foodmap.restaurant.support.RestaurantIdentifiers;
import com.foodmap.restaurant.vo.RestaurantPickerVo;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 独立的无状态只读选店查询，不使用目录/ES访问窗口、不写抽取记录、不调用外部推荐服务。 */
@Service
@RequiredArgsConstructor
public class RestaurantPickerService {
    private final RestaurantCatalogMapper mapper;
    private final RestaurantReadService restaurants;
    private final RestaurantRankingProperties campusProperties;

    /** ID筛选、抽样及卡片资料使用同一RR快照；复核不受排除ID影响，也不会随机补入别家。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RestaurantPickerVo candidates(RestaurantPickerRequest request) {
        var query = query(request);
        var excluded =
                new HashSet<>(
                        request.getExcludeIds().stream().map(RestaurantIdentifiers::id).toList());
        var check =
                request.getCheckIds().stream().map(RestaurantIdentifiers::id).distinct().toList();
        // 直接比较五分制口碑生成列，不混用十分制均分，也不按前端四舍五入后的文字过滤。
        var minRating =
                "ANY".equals(request.getMinRating())
                        ? null
                        : new BigDecimal(request.getMinRating());
        var all = mapper.selectPickerIds(query, minRating);
        var remaining = all.stream().filter(id -> !excluded.contains(id)).toList();
        List<Long> selected;
        if (check.isEmpty()) {
            selected = RestaurantPickerPolicy.sample(remaining, 5, ThreadLocalRandom.current());
        } else {
            var eligible = new HashSet<>(all);
            selected = check.stream().filter(eligible::contains).toList();
        }
        var result = new RestaurantPickerVo();
        result.setRecords(restaurants.pickerRecords(query, selected));
        result.setMatchedCount(all.size());
        result.setRemainingCount(remaining.size() - (check.isEmpty() ? selected.size() : 0));
        return result;
    }

    /** HTTP不可覆盖普通餐厅/经营状态；沿用现有SQL对未知价格、位置和用餐方式的严格语义。 */
    private RestaurantListQuery query(RestaurantPickerRequest request) {
        if (!"ANY".equals(request.getCategory())
                && mapper.selectCategories().stream()
                        .noneMatch(
                                category ->
                                        category.isEnabled()
                                                && category.getCode().equals(request.getCategory())
                                                && !"CANTEEN".equals(category.getCode()))) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT, "请选择有效餐厅类别");
        }
        var query = new RestaurantListQuery();
        query.setRestaurantType("REGULAR");
        query.setHideClosed(true);
        query.setMaxDistanceMeters(request.getDistanceMeters());
        query.setMaxPrice(
                "ANY".equals(request.getBudget()) ? null : new BigDecimal(request.getBudget()));
        query.setCategory("ANY".equals(request.getCategory()) ? "" : request.getCategory());
        query.setDiningMode(
                switch (request.getDiningMode()) {
                    case "DINE_IN" -> "dineIn";
                    case "DELIVERY" -> "delivery";
                    default -> "";
                });
        if ("USER".equals(request.getRange())) {
            query.setLatitude(request.getLatitude());
            query.setLongitude(request.getLongitude());
        } else {
            var campus =
                    "SHAHE".equals(request.getRange())
                            ? campusProperties.getShahe()
                            : campusProperties.getQingshuihe();
            query.setLatitude(campus.getLatitude());
            query.setLongitude(campus.getLongitude());
        }
        return query;
    }
}
