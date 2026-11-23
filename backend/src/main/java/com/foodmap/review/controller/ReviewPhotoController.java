package com.foodmap.review.controller;

import com.foodmap.review.service.ReviewPhotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 正式照片为公开内容，访问路径绑定点评与位置，不暴露任意OSS Key。 */
@RestController
@RequiredArgsConstructor
public class ReviewPhotoController {
    private final ReviewPhotoService photos;
    /** 禁止缓存，后续请求仍需校验点评状态；已传输到客户端的字节不能撤回。 */
    @GetMapping("/api/reviews/{reviewId}/photos/{position}")
    public ResponseEntity<byte[]> read(@PathVariable String reviewId,@PathVariable String position) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
            .header("X-Content-Type-Options","nosniff").body(photos.read(reviewId,position));
    }
}
