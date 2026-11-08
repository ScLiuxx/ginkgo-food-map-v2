package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.restaurant.dto.RestaurantReviewTarget;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantReviewTargetMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 跨模块公开读取入口；本人点评先确定全部可见目标，再计数和分页，避免页内过滤漏记录。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RestaurantReviewTargetService {
    private final RestaurantReviewTargetMapper mapper;
    private final CanteenWindowService windows;

    /** 窗口与普通餐厅分别返回，数值ID相同时不覆盖另一种对象。 */
    public Map<Long, RestaurantReviewTarget> visibleWindows(List<Long> ids) {
        return windows.targets(ids);
    }

    /** 只批量补全调用者提供的目标，不扫描全站目录；分批限制SQL参数，失败不能当成无可见店铺。 */
    public Map<Long, RestaurantReviewTarget> visible(List<Long> candidateIds) {
        var ids = candidateIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        var result = new LinkedHashMap<Long, RestaurantReviewTarget>();
        for (int start = 0; start < ids.size(); start += 500) {
            for (var row : mapper.visible(ids.subList(start, Math.min(start + 500, ids.size())))) {
                var target = new RestaurantReviewTarget();
                target.setId(row.getId().toString());
                target.setName(row.getName());
                target.setBusinessStatus(row.getBusinessStatus());
                result.put(row.getId(), target);
            }
        }
        return result;
    }
}
