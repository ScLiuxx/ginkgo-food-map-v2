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

/** 受认证保护的纠错提交入口，不提供未经管理员授权的公开资料更新动作。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/restaurants/{id}/change-requests")
public class RestaurantChangeController {
    private final RestaurantChangeService service;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String,String>>> submit(@PathVariable String id,
            @Valid @RequestBody RestaurantChangeRequest request) {
        String requestId = service.submit(id, request);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(Map.of("id",requestId,"status","PENDING")));
    }
}
