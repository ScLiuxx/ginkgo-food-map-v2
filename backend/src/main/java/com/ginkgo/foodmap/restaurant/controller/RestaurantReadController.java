package com.ginkgo.foodmap.restaurant.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.service.RestaurantReadService;
import com.ginkgo.foodmap.restaurant.vo.RestaurantCategoryVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantPageVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantMapPointsVo;
import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

import java.util.List;

/** 公开只读HTTP入口；安全配置按GET及具体路径放行，未来写入接口仍默认需要认证。 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RestaurantReadController {
    private final RestaurantReadService service;

    /** 只绑定公开字段；已移除的杯型/分档视图明确拒绝，避免静默忽略后返回误导结果。 */
    @InitBinder("restaurantListQuery")
    void bindQuery(WebDataBinder binder, NativeWebRequest request) {
        if (request.getParameterMap().keySet().stream()
                .anyMatch(key -> key.equals("view") || key.equals("tiers") || key.startsWith("tiers["))) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT, "已不支持杯型或评分分档视图筛选");
        }
        binder.setAllowedFields("keyword", "category", "sort", "hideClosed",
                "latitude", "longitude", "maxPrice", "maxDistanceMeters", "page", "pageSize", "west", "south", "east", "north");
    }

    /** 分类字典无需登录，返回稳定编码及展示顺序。 */
    @GetMapping("/restaurant-categories")
    public ApiResponse<List<RestaurantCategoryVo>> categories() {
        return ApiResponse.ok(service.categories());
    }

    /** 非法类型和约束错误由统一校验处理器转换为400，不回显SQL或内部异常。 */
    @GetMapping("/restaurants")
    public ApiResponse<RestaurantPageVo> list(@Valid @ModelAttribute RestaurantListQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /** 固定子路径优先于详情ID匹配，仅公开只读点位接口。 */
    @GetMapping("/restaurants/map-points")
    public ApiResponse<RestaurantMapPointsVo> mapPoints(@Valid @ModelAttribute RestaurantListQuery query) {
        return ApiResponse.ok(service.mapPoints(query));
    }

    /** 详情没有评价明细、图片或管理字段，后续能力单独定义接口。 */
    @GetMapping("/restaurants/{id}")
    public ApiResponse<RestaurantVo> detail(@PathVariable String id) {
        return ApiResponse.ok(service.detail(id));
    }
}
