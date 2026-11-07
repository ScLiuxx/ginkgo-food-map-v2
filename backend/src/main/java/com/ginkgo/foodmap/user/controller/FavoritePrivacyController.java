package com.ginkgo.foodmap.user.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.security.service.CurrentActorService;
import com.ginkgo.foodmap.user.dto.FavoriteVisibilityRequest;
import com.ginkgo.foodmap.user.service.FavoritePrivacyService;
import com.ginkgo.foodmap.user.vo.FavoriteVisibilityVo;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 设置身份仅来自认证上下文，不接受路径或请求中的其他用户ID。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/me/favorites/visibility")
public class FavoritePrivacyController {
    private final FavoritePrivacyService privacy;
    private final CurrentActorService actors;

    @GetMapping
    public ResponseEntity<ApiResponse<FavoriteVisibilityVo>> get() {
        return response(privacy.get(actors.requireActor().getUserId()));
    }

    @PatchMapping
    public ResponseEntity<ApiResponse<FavoriteVisibilityVo>> set(
            @Valid @RequestBody FavoriteVisibilityRequest request) {
        return response(
                privacy.set(actors.requireActor().getUserId(), request.getFavoritesPublic()));
    }

    private ResponseEntity<ApiResponse<FavoriteVisibilityVo>> response(FavoriteVisibilityVo value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));
    }
}
