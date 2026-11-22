package com.foodmap.restaurant.controller;

import com.foodmap.common.api.ApiResponse;
import com.foodmap.restaurant.service.RestaurantPhotoService;
import com.foodmap.review.dto.RestaurantReviewPhotos;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 候选仅限当前可见商户，直接图片上传沿用RESTAURANT用途，最终写入由资料保存事务完成。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin/restaurants/{id}/review-photos")
public class RestaurantPhotoManagementController {
    private final RestaurantPhotoService photos;

    @GetMapping
    public ResponseEntity<ApiResponse<RestaurantReviewPhotos.Page>> page(
            @PathVariable String id, @RequestParam(defaultValue = "1") int page) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(photos.reviewPhotos(id, page)));
    }
}
