package com.ginkgo.foodmap.review.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.review.dto.ReviewListQuery;
import com.ginkgo.foodmap.review.service.MyReviewReadService;
import com.ginkgo.foodmap.review.vo.MyReviewPageVo;
import com.ginkgo.foodmap.security.service.CurrentActorService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

/** 本人点评入口不接受账号选择；默认安全过滤链及方法鉴权共同保护私有列表。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MyReviewController {
    private final MyReviewReadService reviews;
    private final CurrentActorService actors;

    /** 只绑定分页，客户端提供的身份及派生offset不能改变服务端查询范围。 */
    @InitBinder("reviewListQuery")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("page", "pageSize");
    }

    /** 私有动态内容禁止缓存；跨请求有新点评或删除时按当前数据重新归组。 */
    @GetMapping("/api/me/reviews")
    public ResponseEntity<ApiResponse<MyReviewPageVo>> list(
            @Valid @ModelAttribute ReviewListQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(reviews.list(actors.requireActor().getUserId(), query)));
    }
}
