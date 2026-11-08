package com.ginkgo.foodmap.review.controller;

import com.ginkgo.foodmap.review.service.ReplyDeleteService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 本人回复删除入口，归属与重复请求授权由应用服务核验。 */
@RestController
@RequiredArgsConstructor
public class ReplyDeleteController {
    private final ReplyDeleteService service;

    /** 首次与原作者重复删除均204，不缓存带身份含义的操作回执。 */
    @DeleteMapping("/api/review-replies/{replyId}")
    public ResponseEntity<Void> remove(@PathVariable String replyId) {
        service.remove(replyId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
