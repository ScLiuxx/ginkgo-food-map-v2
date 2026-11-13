package com.ginkgo.foodmap.restaurant.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSubmissionRequest;
import com.ginkgo.foodmap.restaurant.service.RestaurantChangeService;
import com.ginkgo.foodmap.restaurant.vo.RestaurantSubmissionVo;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 登录用户的新商户投稿入口；成功仅代表进入待审队列，不签发正式商户ID。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/restaurant-submissions")
public class RestaurantSubmissionController {
    private final RestaurantChangeService service;

    /** 接收完整投稿及可选点评，账号取认证上下文；201仅表示待审申请及待发布点评均已保存。 */
    @PostMapping
    public ResponseEntity<ApiResponse<RestaurantSubmissionVo>> submit(
            @Valid @RequestBody RestaurantSubmissionRequest request) {
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.submitNewWithReview(request)));
    }
}
