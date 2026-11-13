package com.ginkgo.foodmap.restaurant.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.restaurant.dto.RestaurantPickerRequest;
import com.ginkgo.foodmap.restaurant.service.RestaurantPickerService;
import com.ginkgo.foodmap.restaurant.vo.RestaurantPickerVo;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 请求体承载临时条件与排除ID，避免长URL；POST仅执行公开只读查询，不创建服务端会话。 */
@RestController
@RequiredArgsConstructor
public class RestaurantPickerController {
    private final RestaurantPickerService service;

    /** 先执行完整输入校验，返回候选或空集合；无结果不提高预算或扩大距离。 */
    @PostMapping("/api/restaurant-picker/candidates")
    public ApiResponse<RestaurantPickerVo> candidates(
            @Valid @RequestBody RestaurantPickerRequest request) {
        return ApiResponse.ok(service.candidates(request));
    }
}
