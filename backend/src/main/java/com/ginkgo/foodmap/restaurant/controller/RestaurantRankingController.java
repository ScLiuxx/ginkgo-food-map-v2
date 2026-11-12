package com.ginkgo.foodmap.restaurant.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.restaurant.dto.RestaurantRankingQuery;
import com.ginkgo.foodmap.restaurant.service.RestaurantRankingReadService;
import com.ginkgo.foodmap.restaurant.vo.RestaurantRankingVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公开榜单只读入口；安全配置仅放行这个GET，不能用于写快照或触发计算。 */
@RestController
@RequestMapping("/api/restaurant-rankings")
@RequiredArgsConstructor
public class RestaurantRankingController {
    private final RestaurantRankingReadService service;

    /** 不绑定状态、分页、来源或TTL等内部字段。 */
    @InitBinder
    void bindQuery(WebDataBinder binder) {
        binder.setAllowedFields("campusCode", "periodType");
    }

    /** 参数先校验再调用应用服务；没有快照时返回准备中，页面入口留待后续接入。 */
    @GetMapping
    public ApiResponse<RestaurantRankingVo> read(@Valid @ModelAttribute RestaurantRankingQuery query) {
        return ApiResponse.ok(service.read(query.getCampusCode(), query.getPeriodType()));
    }
}
