package com.ginkgo.foodmap.restaurant.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.restaurant.dto.RestaurantChangeRequest;
import com.ginkgo.foodmap.restaurant.service.RestaurantChangeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 登录用户的新商户投稿入口；成功仅代表进入待审队列，不签发正式商户ID。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/restaurant-submissions")
public class RestaurantSubmissionController {
    private final RestaurantChangeService service;

    /** 接收完整投稿资料，账号取认证上下文；201仅表示待审申请已保存。 */
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String,String>>> submit(@Valid @RequestBody RestaurantChangeRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(Map.of("id", service.submitNew(request), "status", "PENDING")));
    }
}
