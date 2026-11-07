package com.ginkgo.foodmap.restaurant.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.restaurant.dto.RestaurantPhotoQuery;
import com.ginkgo.foodmap.restaurant.service.RestaurantPhotoService;
import com.ginkgo.foodmap.restaurant.vo.RestaurantPhotoViews;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

/** 正式相册匿名GET入口；只允许公开关联ID读取，响应不缓存以保留后续可见性检查。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/restaurants/{id}/photos")
public class RestaurantPhotoController {
    private final RestaurantPhotoService service;

    /** 查询只允许游标/数量，不能通过绑定注入排序位置或内部文件ID。 */
    @InitBinder("restaurantPhotoQuery")
    void bindQuery(WebDataBinder binder) {
        binder.setAllowedFields("cursor", "limit");
    }

    /** 有界分页，records为空仍200，nextCursor为空表示本次读取没有后续记录。 */
    @GetMapping
    public ResponseEntity<ApiResponse<RestaurantPhotoViews.Page>> page(
            @PathVariable String id, @Valid @ModelAttribute RestaurantPhotoQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.page(id, query)));
    }

    /** 原始JPEG字节，不返回签名OSS地址；异常由统一处理器返回规范错误。 */
    @GetMapping("/{photoId}")
    public ResponseEntity<byte[]> photo(@PathVariable String id, @PathVariable String photoId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff")
                .body(service.photo(id, photoId));
    }
}
