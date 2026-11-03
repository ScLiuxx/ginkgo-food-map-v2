package com.ginkgo.foodmap.admin.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.restaurant.dto.RestaurantRequestQuery;
import com.ginkgo.foodmap.restaurant.dto.RestaurantRejectRequest;
import com.ginkgo.foodmap.restaurant.dto.RestaurantApproveRequest;
import com.ginkgo.foodmap.restaurant.service.RestaurantRequestReadService;
import com.ginkgo.foodmap.restaurant.service.RestaurantRequestReviewService;
import com.ginkgo.foodmap.restaurant.vo.RestaurantDecisionVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantRequestViews;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 管理员申请查询协议入口；权限由管理安全链及restaurant应用服务双重校验。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/restaurant-requests")
public class AdminRestaurantRequestController {
    private final RestaurantRequestReadService service;
    private final RestaurantRequestReviewService reviews;

    /** 完整业务资料经服务校验后发布；返回终态回执，首次和重复审核均为200。 */
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<RestaurantDecisionVo>> approve(
            @PathVariable String id, @Valid @RequestBody RestaurantApproveRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(reviews.approve(id, request)));
    }

    /** 原因标签或文字原因都进入同一事务服务；不会删除投稿图片或改变正式商户。 */
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<RestaurantDecisionVo>> reject(
            @PathVariable String id, @Valid @RequestBody RestaurantRejectRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(reviews.reject(id, request.getReason())));
    }

    /** 有界分页及状态计数；无结果仍返回200，不能通过普通账号访问。 */
    @GetMapping
    public ResponseEntity<ApiResponse<RestaurantRequestViews.Page>> page(@Valid @ModelAttribute RestaurantRequestQuery query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.page(query)));
    }

    /** 返回白名单申请、当前业务资料和图片引用，不在详情查询中读取云对象。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RestaurantRequestViews.Detail>> detail(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.detail(id)));
    }

    /** 使用原始JPEG字节与no-store，不返回可绕过后续权限校验的OSS地址。 */
    @GetMapping("/{id}/photos/{position}")
    public ResponseEntity<byte[]> photo(@PathVariable String id, @PathVariable String position) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff").body(service.photo(id, position));
    }
}
