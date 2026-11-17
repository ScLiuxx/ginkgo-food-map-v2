package com.foodmap.review.controller;

import com.foodmap.review.service.ReviewDeleteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 本人删评HTTP入口，不接受作者ID或客户端权限标记。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reviews")
public class ReviewDeleteController {
    private final ReviewDeleteService service;

    /** 无内容成功回执；已完成删除的本人重试同样204，禁止缓存。 */
    @DeleteMapping("/{reviewId}")
    public ResponseEntity<Void> remove(@PathVariable String reviewId) {
        service.remove(reviewId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
