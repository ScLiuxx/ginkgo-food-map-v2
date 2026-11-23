package com.foodmap.review.controller;

import com.foodmap.common.api.ApiResponse;
import com.foodmap.review.dto.ReviewPublishRequest;
import com.foodmap.review.service.ReviewWriteService;
import com.foodmap.review.vo.ReviewPublishVo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 点评发布HTTP入口；支持文本与有序文件ID，作者只能来自安全上下文。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/restaurants/{restaurantId}/reviews")
public class ReviewWriteController {
    private final ReviewWriteService service;

    /** 首次201、同内容重放200；不提供指向未实现GET接口的Location。 */
    @PostMapping
    public ResponseEntity<ApiResponse<ReviewPublishVo>> publish(@PathVariable String restaurantId,
            @RequestBody ReviewPublishRequest request) {
        var result = service.publish(restaurantId,request);
        return ResponseEntity.status(result.isReplayed()?200:201).cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }
}
