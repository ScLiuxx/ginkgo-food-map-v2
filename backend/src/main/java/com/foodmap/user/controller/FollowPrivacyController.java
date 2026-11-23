package com.foodmap.user.controller;

import com.foodmap.common.api.ApiResponse;
import com.foodmap.security.service.CurrentActorService;
import com.foodmap.user.dto.FollowPrivacyRequest;
import com.foodmap.user.service.FollowUserService;
import com.foodmap.user.vo.FollowPrivacyVo;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 仅修改认证本人的名单隐私，客户端不能指定其他用户。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/me/follows/privacy")
public class FollowPrivacyController {
    private final FollowUserService users;
    private final CurrentActorService actors;

    @GetMapping
    public ResponseEntity<ApiResponse<FollowPrivacyVo>> get() {
        return response(users.privacy(actors.requireActor().getUserId()));
    }

    @PatchMapping
    public ResponseEntity<ApiResponse<FollowPrivacyVo>> set(
            @Valid @RequestBody FollowPrivacyRequest request) {
        return response(
                users.set(
                        actors.requireActor().getUserId(),
                        request.getFollowingPublic(),
                        request.getFollowersPublic()));
    }

    private ResponseEntity<ApiResponse<FollowPrivacyVo>> response(FollowPrivacyVo value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));
    }
}
