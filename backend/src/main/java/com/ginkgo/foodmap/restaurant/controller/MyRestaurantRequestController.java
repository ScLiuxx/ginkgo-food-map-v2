package com.ginkgo.foodmap.restaurant.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.restaurant.dto.MyRestaurantRequestQuery;
import com.ginkgo.foodmap.restaurant.service.MyRestaurantRequestService;
import com.ginkgo.foodmap.restaurant.vo.MyRestaurantRequestViews;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

/** 本人申请只读入口，与管理员队列和审核权限隔离，不开放修改/撤回接口。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/me/restaurant-requests")
public class MyRestaurantRequestController {
    private final MyRestaurantRequestService service;

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
