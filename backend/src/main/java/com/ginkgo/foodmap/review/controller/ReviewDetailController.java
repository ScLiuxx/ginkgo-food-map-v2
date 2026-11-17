package com.ginkgo.foodmap.review.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.review.service.ReviewDetailService;
import com.ginkgo.foodmap.review.vo.ReviewDetailVo;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 单条点评精确定位入口；沿用默认认证，不扩大匿名路由白名单。 */
@RestController
@RequiredArgsConstructor
public class ReviewDetailController {
    private final ReviewDetailService service;

    /** 动态内容禁止缓存，已删除内容返回404，不跳转其他点评或店铺。 */
    @GetMapping("/api/reviews/{reviewId}")
    public ResponseEntity<ApiResponse<ReviewDetailVo>> detail(@PathVariable String reviewId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.detail(reviewId)));
    }
}
