package com.ginkgo.foodmap.review.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.review.dto.ReviewHistoryQuery;
import com.ginkgo.foodmap.review.dto.ReviewListQuery;
import com.ginkgo.foodmap.review.service.ReviewReadService;
import com.ginkgo.foodmap.review.vo.ReviewHistoryVo;
import com.ginkgo.foodmap.review.vo.ReviewPageVo;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

/** 公开作者组读取HTTP入口；不依赖登录身份，也不输出删除等管理能力。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/restaurants/{restaurantId}/reviews")
public class ReviewReadController {
    private final ReviewReadService service;

    /** 只接受分页与排序字段，派生offset由服务端计算。 */
    @InitBinder("reviewListQuery")
    void bindQuery(WebDataBinder binder) {
        binder.setAllowedFields("page", "pageSize", "sort");
    }

    /** 历史只绑定游标与段大小，作者身份来自路径且不代表任何写入权限。 */
    @InitBinder("reviewHistoryQuery")
    void bindHistory(WebDataBinder binder) {
        binder.setAllowedFields("cursor", "pageSize");
    }

    /** 公开历史读取，返回当前代表ID；每段仍重新验证父店可见性。 */
    @GetMapping("/authors/{authorId}/history")
    public ResponseEntity<ApiResponse<ReviewHistoryVo>> history(
            @PathVariable String restaurantId,
            @PathVariable String authorId,
            @Valid @ModelAttribute ReviewHistoryQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.history(restaurantId, authorId, query)));
    }

    /** 动态列表不缓存；一次响应内一致，跨请求有新评时作者可能移到前页。 */
    @GetMapping
    public ResponseEntity<ApiResponse<ReviewPageVo>> list(
            @PathVariable String restaurantId, @Valid @ModelAttribute ReviewListQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.list(restaurantId, query)));
    }
}
