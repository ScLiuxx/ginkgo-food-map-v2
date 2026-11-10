package com.ginkgo.foodmap.restaurant.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.restaurant.dto.MyRestaurantRequestQuery;
import com.ginkgo.foodmap.restaurant.dto.RestaurantChangeEditRequest;
import com.ginkgo.foodmap.restaurant.dto.RestaurantChangeRequest;
import com.ginkgo.foodmap.restaurant.service.MyRestaurantRequestService;
import com.ginkgo.foodmap.restaurant.service.RestaurantChangeService;
import com.ginkgo.foodmap.restaurant.vo.MyRestaurantRequestViews;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 本人申请入口：提供本人只读查询、待审修改/撤回和驳回重提，不开放管理员审核权限。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/me/restaurant-requests")
public class MyRestaurantRequestController {
    private final MyRestaurantRequestService service;
    private final RestaurantChangeService submissions;

    /** 绑定白名单不接受submittedBy/userId或任意SQL排序字段。 */
    @InitBinder("myRestaurantRequestQuery")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("status", "page", "pageSize");
    }

    /** 私有分页禁止缓存，空列表仍是正常200；查询故障不能伪装为空。 */
    @GetMapping
    public ResponseEntity<ApiResponse<MyRestaurantRequestViews.Page>> page(
            @Valid @ModelAttribute MyRestaurantRequestQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.page(query)));
    }

    /** 不存在与越权统一404，业务服务始终从认证上下文取得本人身份。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MyRestaurantRequestViews.Detail>> detail(
            @PathVariable String id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.detail(id)));
    }

    /** 整体替换本人待审申请；仅PENDING可改，已完成审核或撤回返回409。 */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, String>>> edit(
            @PathVariable String id, @Valid @RequestBody RestaurantChangeEditRequest request) {
        service.edit(id, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(Map.of("id", id, "status", "PENDING")));
    }

    /** 撤回本人待审申请；重复撤回幂等返回成功，不改变审核结果或公开资料。 */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<ApiResponse<Map<String, String>>> withdraw(@PathVariable String id) {
        service.withdraw(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(Map.of("id", id, "status", "WITHDRAWN")));
    }

    /** 基于本人被驳回申请创建新申请；原驳回记录与原因保持不变。 */
    @PostMapping("/{id}/resubmit")
    public ResponseEntity<ApiResponse<Map<String, String>>> resubmit(
            @PathVariable String id, @Valid @RequestBody RestaurantChangeRequest request) {
        String requestId = submissions.resubmit(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(Map.of("id", requestId, "status", "PENDING")));
    }

    /** 返回需逐次鉴权的JPEG字节，不签发可绕过退出/权限校验的对象地址。 */
    @GetMapping("/{id}/photos/{position}")
    public ResponseEntity<byte[]> photo(@PathVariable String id, @PathVariable String position) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff")
                .body(service.photo(id, position));
    }
}
