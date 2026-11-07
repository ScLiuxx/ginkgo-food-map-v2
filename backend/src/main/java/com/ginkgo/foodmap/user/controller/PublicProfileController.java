package com.ginkgo.foodmap.user.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.review.dto.ReviewListQuery;
import com.ginkgo.foodmap.review.vo.MyReviewPageVo;
import com.ginkgo.foodmap.user.service.PublicProfileService;
import com.ginkgo.foodmap.user.vo.PublicProfileVo;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

/** 公开主页仅向登录用户开放；路径用户是查看对象，绝不作为写操作身份。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/users/{userId}")
public class PublicProfileController {
    private final PublicProfileService profiles;

    /** 分组仍按最新点评时间排序，禁止绑定作者身份、派生offset或商户点评排序。 */
    @InitBinder("reviewListQuery")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("page", "pageSize");
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<PublicProfileVo>> profile(@PathVariable String userId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(profiles.get(userId)));
    }

    @GetMapping("/reviews")
    public ResponseEntity<ApiResponse<MyReviewPageVo>> reviews(
            @PathVariable String userId, @Valid @ModelAttribute ReviewListQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(profiles.reviews(userId, query)));
    }
}
